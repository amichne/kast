package io.github.amichne.kast.distribution.cli

import com.github.ajalt.clikt.core.Context
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import sun.misc.Signal

internal val publicQueryNamespaces = setOf("query", "result", "source", "diagnostic", "describe", "hook", "workspace")

internal class PublicQueryNamespace(private val namespace: String) : ManagementNode(namespace) {
    override fun help(context: Context) =
        when (namespace) {
            "query" -> "Find Kotlin declarations and read source relationships."
            "result" -> "Read or resume a retained query result."
            "source" -> "Read source from an issued reference."
            "diagnostic" -> "Read compiler diagnostics for a selected path."
            "describe" -> "Discover installed commands, schemas, and examples."
            "hook" -> "Prepare a supported workspace through the shared session hook."
            "workspace" -> "Wait for the selected workspace to become ready."
            else -> error("Unknown public query namespace: $namespace")
        }

    override fun selection(): ManagementCommand? = null
}

/** The native adapter admits installation ownership, never semantic syntax. */
internal fun runInstalledPublicQuery(
    arguments: List<String>,
    directory: Path,
    environment: Map<String, String>,
    output: ManagementOutput = ManagementOutput(),
): Int =
    when (val launcher = selectPublicQueryLauncher(environment)) {
        is PublicQueryLauncher.Admitted ->
            try {
                runOwnedPublicQueryChild(listOf(launcher.path.toString()) + arguments, directory, environment)
            } catch (_: IOException) {
                publishPublicQueryStartupFailure(PublicQueryStartupFailure.CATALOG_UNAVAILABLE, output)
            } catch (_: RuntimeException) {
                publishPublicQueryStartupFailure(PublicQueryStartupFailure.INVOCATION_FAILED, output)
            }
        PublicQueryLauncher.Unavailable ->
            publishPublicQueryStartupFailure(PublicQueryStartupFailure.CATALOG_UNAVAILABLE, output)
    }

internal sealed interface PublicQueryLauncher {
    class Admitted private constructor(val path: Path) : PublicQueryLauncher {
        companion object {
            fun fromInstallation(installation: Path, manifest: BundledManifest): PublicQueryLauncher {
                val relative = "bin/kast-query-complete"
                val path = installation.resolve(relative)
                val executablePayloads = listOf("bin/kast-query", relative, "share/kast/libexec/kast-management")
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

private fun publishPublicQueryStartupFailure(failure: PublicQueryStartupFailure, output: ManagementOutput): Int {
    output.publish(managementJson.encodeToString(PublicQueryStartupRejection(failure)))
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
    output: OwnedChildOutput = OwnedChildOutput.Inherited,
): Int = OwnedChildSession().run(command, directory, environment, output)

/** One process-entry signal owner spans setup children, gaps, and the final task. */
internal class OwnedChildSession {
    private val owner = PublicQueryChildOwner()

    init {
        Signal.handle(Signal("INT")) { cancel(PublicQuerySignal.INTERRUPT) }
        Signal.handle(Signal("TERM")) { cancel(PublicQuerySignal.TERMINATE) }
    }

    fun cancel(signal: PublicQuerySignal) = owner.cancel(signal)

    fun run(
        command: List<String>,
        directory: Path,
        environment: Map<String, String>,
        output: OwnedChildOutput = OwnedChildOutput.Inherited,
        onCancel: (PublicQuerySignal, Process) -> Unit = { _, _ -> },
    ): Int =
        owner.run(output, onCancel) {
            ProcessBuilder(command)
                .directory(directory.toFile())
                .apply {
                    environment().clear()
                    environment().putAll(environment)
                }
                .inheritIO()
                .apply {
                    if (output != OwnedChildOutput.Inherited) redirectOutput(ProcessBuilder.Redirect.PIPE)
                    if (output is OwnedChildOutput.Capture) redirectInput(ProcessBuilder.Redirect.PIPE)
                }
                .start()
        }
}

private sealed interface PublicQueryChildState {
    data object Pending : PublicQueryChildState

    data class Cancelled(val signal: PublicQuerySignal) : PublicQueryChildState

    data class Running(val child: Process, val onCancel: (PublicQuerySignal, Process) -> Unit) : PublicQueryChildState

    data object Reaped : PublicQueryChildState
}

private class PublicQueryChildOwner {
    private var state: PublicQueryChildState = PublicQueryChildState.Pending

    fun run(
        output: OwnedChildOutput,
        onCancel: (PublicQuerySignal, Process) -> Unit,
        start: () -> Process,
    ): Int {
        val started =
            synchronized(this) {
                when (val current = state) {
                    PublicQueryChildState.Pending,
                    PublicQueryChildState.Reaped -> PublicQueryChildState.Running(start(), onCancel).also { state = it }
                    is PublicQueryChildState.Cancelled -> current
                    is PublicQueryChildState.Running -> error("Owned child already running")
                }
            }
        return when (started) {
            is PublicQueryChildState.Cancelled -> started.signal.exit
            is PublicQueryChildState.Running -> reap(started.child, output)
            PublicQueryChildState.Pending,
            PublicQueryChildState.Reaped -> error("Public query child did not start")
        }
    }

    @Synchronized
    fun cancel(signal: PublicQuerySignal) {
        when (val current = state) {
            PublicQueryChildState.Pending,
            PublicQueryChildState.Reaped -> state = PublicQueryChildState.Cancelled(signal)
            is PublicQueryChildState.Running -> {
                state = PublicQueryChildState.Cancelled(signal)
                try {
                    if (current.child.isAlive) current.onCancel(signal, current.child)
                } finally {
                    forwardPublicQuerySignal(current.child, signal)
                }
            }
            is PublicQueryChildState.Cancelled -> Unit
        }
    }

    private fun reap(child: Process, output: OwnedChildOutput): Int =
        try {
            val delivery = deliverOwnedChildOutput(child, output)
            if (delivery is ChildOutputDelivery.Rejected && child.isAlive) child.destroyForcibly()
            val status = child.waitFor()
            completedExit(status, delivery)
        } catch (_: InterruptedException) {
            cancel(PublicQuerySignal.TERMINATE)
            child.destroy()
            waitForOwnedChild(child)
            Thread.currentThread().interrupt()
            PublicQuerySignal.TERMINATE.exit
        } finally {
            if (child.isAlive) {
                child.destroyForcibly()
                waitForOwnedChild(child)
            }
            synchronized(this) {
                if (state !is PublicQueryChildState.Cancelled) state = PublicQueryChildState.Reaped
            }
        }

    private fun completedExit(status: Int, delivery: ChildOutputDelivery): Int =
        synchronized(this) {
            when (val current = state) {
                is PublicQueryChildState.Cancelled -> current.signal.exit
                is PublicQueryChildState.Running ->
                    when (delivery) {
                        ChildOutputDelivery.Delivered -> status
                        is ChildOutputDelivery.Rejected ->
                            if (status in PublicQuerySignal.entries.map { it.exit }) status else delivery.exit
                    }
                PublicQueryChildState.Pending,
                PublicQueryChildState.Reaped -> error("Owned child lost before reap")
            }
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
