package io.github.amichne.kast.distribution.cli

import com.github.ajalt.clikt.core.Context
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import sun.misc.Signal

internal val publicQueryNamespaces = setOf("query", "result", "source", "describe")

internal class PublicQueryNamespace(name: String) : ManagementNode(name) {
    override fun help(context: Context) = "Use the public query CLI and its packaged contract."

    override fun selection(): ManagementCommand? = null
}

/** The native adapter admits installation ownership, never semantic syntax. */
internal fun runInstalledPublicQuery(
    arguments: List<String>,
    directory: Path,
    environment: Map<String, String>,
): Int =
    when (val launcher = selectPublicQueryLauncher(environment)) {
        is PublicQueryLauncher.Admitted ->
            try {
                runOwnedPublicQueryChild(listOf(launcher.path.toString()) + arguments, directory, environment)
            } catch (_: IOException) {
                publishPublicQueryStartupFailure(PublicQueryStartupFailure.CATALOG_UNAVAILABLE)
            } catch (_: RuntimeException) {
                publishPublicQueryStartupFailure(PublicQueryStartupFailure.INVOCATION_FAILED)
            }
        PublicQueryLauncher.Unavailable ->
            publishPublicQueryStartupFailure(PublicQueryStartupFailure.CATALOG_UNAVAILABLE)
    }

internal sealed interface PublicQueryLauncher {
    class Admitted private constructor(val path: Path) : PublicQueryLauncher {
        companion object {
            fun fromInstallation(installation: Path, manifest: BundledManifest): PublicQueryLauncher {
                val relative = "bin/kast-query-complete"
                val path = installation.resolve(relative)
                val executablePayloads = listOf("bin/kast-query", relative)
                return if (
                    executablePayloads.all {
                        payloadOwned(installation, manifest, it) && Files.isExecutable(installation.resolve(it))
                    }
                )
                    Admitted(path)
                else Unavailable
            }
        }
    }

    data object Unavailable : PublicQueryLauncher
}

private fun selectPublicQueryLauncher(environment: Map<String, String>): PublicQueryLauncher =
    try {
        when (val root = resolveManagementRoot(environment)) {
            is ManagementRootResolution.Rejected -> PublicQueryLauncher.Unavailable
            is ManagementRootResolution.Selected -> {
                val installation = selectedInstallation(root.root)
                when (val manifest = readBundledManifest(installation)) {
                    is BundledManifestRead.Read ->
                        PublicQueryLauncher.Admitted.fromInstallation(installation, manifest.manifest)
                    BundledManifestRead.Invalid,
                    BundledManifestRead.Unavailable -> PublicQueryLauncher.Unavailable
                }
            }
        }
    } catch (_: ManagementRejected) {
        PublicQueryLauncher.Unavailable
    } catch (_: IOException) {
        PublicQueryLauncher.Unavailable
    } catch (_: SecurityException) {
        PublicQueryLauncher.Unavailable
    }

@Serializable
private data class PublicQueryStartupRejection(
    val failure: PublicQueryStartupFailure,
    @Required val type: String = "rejected",
)

@Serializable
@Suppress("MagicNumber") // These literals define the public process contract.
private enum class PublicQueryStartupFailure(val exit: Int) {
    CATALOG_UNAVAILABLE(4),
    INVOCATION_FAILED(1),
}

private fun publishPublicQueryStartupFailure(failure: PublicQueryStartupFailure): Int {
    System.out.println(managementJson.encodeToString(PublicQueryStartupRejection(failure)))
    System.out.flush()
    return if (System.out.checkError()) PUBLIC_OUTPUT_IO_EXIT else failure.exit
}

private const val PUBLIC_OUTPUT_IO_EXIT = 5

@Suppress("MagicNumber") // POSIX signal exit values are fixed external codes.
internal enum class PublicQuerySignal(val signalName: String, val exit: Int) {
    INTERRUPT("INT", 130),
    TERMINATE("TERM", 143),
}

/** The child owns response publication. The parent forwards its status after reaping it. */
internal fun runOwnedPublicQueryChild(
    command: List<String>,
    directory: Path,
    environment: Map<String, String>,
): Int {
    val owner = PublicQueryChildOwner()
    val interrupt = Signal("INT")
    val terminate = Signal("TERM")
    // These process-entry handlers stay installed through exit, including after the child is reaped.
    Signal.handle(interrupt) { owner.cancel(PublicQuerySignal.INTERRUPT) }
    Signal.handle(terminate) { owner.cancel(PublicQuerySignal.TERMINATE) }
    return owner.run {
        ProcessBuilder(command)
            .directory(directory.toFile())
            .apply {
                environment().clear()
                environment().putAll(environment)
            }
            .inheritIO()
            .start()
    }
}

private sealed interface PublicQueryChildState {
    data object Pending : PublicQueryChildState

    data class Cancelled(val signal: PublicQuerySignal) : PublicQueryChildState

    data class Running(val child: Process) : PublicQueryChildState

    data object Reaped : PublicQueryChildState
}

private class PublicQueryChildOwner {
    private var state: PublicQueryChildState = PublicQueryChildState.Pending

    fun run(start: () -> Process): Int {
        val started =
            synchronized(this) {
                when (val current = state) {
                    PublicQueryChildState.Pending -> PublicQueryChildState.Running(start()).also { state = it }
                    is PublicQueryChildState.Cancelled -> current
                    is PublicQueryChildState.Running,
                    PublicQueryChildState.Reaped -> error("Public query child already started")
                }
            }
        return when (started) {
            is PublicQueryChildState.Cancelled -> started.signal.exit
            is PublicQueryChildState.Running -> reap(started.child)
            PublicQueryChildState.Pending,
            PublicQueryChildState.Reaped -> error("Public query child did not start")
        }
    }

    @Synchronized
    fun cancel(signal: PublicQuerySignal) {
        when (val current = state) {
            PublicQueryChildState.Pending -> state = PublicQueryChildState.Cancelled(signal)
            is PublicQueryChildState.Running -> forwardPublicQuerySignal(current.child, signal)
            is PublicQueryChildState.Cancelled,
            PublicQueryChildState.Reaped -> Unit
        }
    }

    private fun reap(child: Process): Int =
        try {
            child.waitFor()
        } catch (_: InterruptedException) {
            child.destroy()
            waitForOwnedChild(child)
            Thread.currentThread().interrupt()
            PublicQuerySignal.TERMINATE.exit
        } finally {
            if (child.isAlive) {
                child.destroyForcibly()
                waitForOwnedChild(child)
            }
            synchronized(this) { state = PublicQueryChildState.Reaped }
        }
}

private fun waitForOwnedChild(child: Process) {
    var interrupted = false
    while (child.isAlive) {
        try {
            child.waitFor()
        } catch (_: InterruptedException) {
            interrupted = true
        }
    }
    if (interrupted) Thread.currentThread().interrupt()
}

private fun forwardPublicQuerySignal(child: Process, signal: PublicQuerySignal) {
    if (child.isAlive) {
        try {
            val sender =
                ProcessBuilder("/bin/kill", "-s", signal.signalName, child.pid().toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            waitForOwnedChild(sender)
            if (sender.exitValue() != 0 && child.isAlive) child.destroy()
        } catch (_: IOException) {
            child.destroy()
        }
    }
}
