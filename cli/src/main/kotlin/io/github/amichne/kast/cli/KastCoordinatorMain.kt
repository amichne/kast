package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.BrokerServerFailure
import io.github.amichne.kast.appserver.BrokerServerRun
import io.github.amichne.kast.appserver.DaemonOperationClientRejection
import io.github.amichne.kast.appserver.InstalledJvmCoordinator
import io.github.amichne.kast.appserver.JvmCoordinatorCommand
import io.github.amichne.kast.appserver.diagnosticCode
import io.github.amichne.kast.cli.direct.InstalledClientContext
import io.github.amichne.kast.cli.publicshell.MANAGEMENT_PAYLOAD
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Explicit JVM entry for the persistent semantic coordinator.
 *
 * The coordinator root is this bytecode's layout: `lib/` holds it, `bin/kast` is the thin frontend, and `state/` holds
 * the coordinator's own publication and socket. `--product` names the installation whose Hosts it manages.
 * - `serve` is the supervised process; the launch contract supplies its readiness environment.
 * - `ensure` starts or joins the published generation through the installation's start lock and supervisor.
 * - `stop` withdraws the supervised job; the coordinator drains the Hosts it owns.
 */
object KastCoordinatorMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val environment = System.getenv()
        val command =
            when (val admitted = admitCoordinatorCommand(arguments.toList(), coordinatorRoot())) {
                is Refinement.Rejected -> {
                    System.err.println(
                        coordinatorJson.encodeToString(CoordinatorRejection.serializer(), admitted.failure)
                    )
                    exitProcess(COORDINATOR_USAGE_EXIT_CODE)
                }
                is Refinement.Refined -> admitted.value
            }
        when (command.action) {
            CoordinatorAction.SERVE -> presentDaemonRun(serve(command, environment))
            CoordinatorAction.ENSURE -> exitProcess(ensure(command, environment))
            CoordinatorAction.STOP -> exitProcess(stop(command, environment))
        }
    }
}

internal enum class CoordinatorAction(val word: String) {
    SERVE("serve"),
    ENSURE("ensure"),
    STOP("stop"),
}

internal class CoordinatorCommand(val root: Path, val product: Path, val action: CoordinatorAction)

@Serializable internal data class CoordinatorRejection(val failure: String)

@Serializable internal data class CoordinatorEnsured(val serviceGeneration: String, val processId: Long)

/** Admits exactly `--product <absolute real path> serve|ensure|stop` for a root whose frontend executable exists. */
internal fun admitCoordinatorCommand(
    arguments: List<String>,
    root: Path?,
): Refinement<CoordinatorCommand, CoordinatorRejection> {
    if (root == null) return Refinement.Rejected(CoordinatorRejection("root-unavailable"))
    val frontend = root.resolve("bin/kast")
    if (!Files.isRegularFile(frontend, NOFOLLOW_LINKS) || !Files.isExecutable(frontend))
        return Refinement.Rejected(CoordinatorRejection("frontend-unavailable"))
    if (arguments.size != COORDINATOR_ARGUMENT_COUNT || arguments[0] != "--product")
        return Refinement.Rejected(CoordinatorRejection("arguments-rejected"))
    val action =
        CoordinatorAction.entries.singleOrNull { it.word == arguments[2] }
            ?: return Refinement.Rejected(CoordinatorRejection("arguments-rejected"))
    val product =
        try {
            Path.of(arguments[1]).takeIf { it.isAbsolute && it.toRealPath() == it }
        } catch (_: InvalidPathException) {
            null
        } catch (_: IOException) {
            null
        } ?: return Refinement.Rejected(CoordinatorRejection("product-rejected"))
    return Refinement.Refined(CoordinatorCommand(root, product, action))
}

private fun serve(command: CoordinatorCommand, environment: Map<String, String>): BrokerServerRun =
    when (val admitted = InstalledClientContext.admit(command.product.resolve(MANAGEMENT_PAYLOAD), environment)) {
        is Refinement.Rejected -> BrokerServerRun.Rejected(BrokerServerFailure.KAST_EXECUTABLE_REJECTED)
        is Refinement.Refined -> runSemanticCoordinator(admitted.value, command.root, environment)
    }

private fun ensure(command: CoordinatorCommand, environment: Map<String, String>): Int {
    val home = home(environment) ?: return rejected("home-rejected")
    val connected = runBlocking {
        InstalledJvmCoordinator.connect(command.root, home, javaHome(), serveCommand(command), environment)
    }
    return when (connected) {
        is Refinement.Refined -> {
            val ensured = CoordinatorEnsured(connected.value.generation, connected.value.processId)
            println(coordinatorJson.encodeToString(CoordinatorEnsured.serializer(), ensured))
            0
        }
        is Refinement.Rejected -> rejected(DaemonOperationClientRejection.Startup(connected.failure).diagnosticCode())
    }
}

private fun stop(command: CoordinatorCommand, environment: Map<String, String>): Int {
    val home = home(environment) ?: return rejected("home-rejected")
    return when (
        val withdrawn = InstalledJvmCoordinator.withdraw(command.root, home, javaHome(), serveCommand(command))
    ) {
        is Refinement.Refined -> 0
        is Refinement.Rejected -> rejected("stop-${withdrawn.failure.name.lowercase().replace('_', '-')}")
    }
}

/** The supervised command is this process's own runtime and class path, so `ensure` and `stop` name the same job. */
private fun serveCommand(command: CoordinatorCommand) =
    JvmCoordinatorCommand(
        System.getProperty("java.class.path"),
        KastCoordinatorMain::class.java.name,
        listOf("--product", command.product.toString(), CoordinatorAction.SERVE.word),
    )

private fun javaHome(): Path = Path.of(System.getProperty("java.home"))

private fun home(environment: Map<String, String>): Path? =
    try {
        environment["HOME"]?.let { Path.of(it).toRealPath() }
    } catch (_: IOException) {
        null
    } catch (_: InvalidPathException) {
        null
    }

private fun rejected(failure: String): Int {
    System.err.println(coordinatorJson.encodeToString(CoordinatorRejection.serializer(), CoordinatorRejection(failure)))
    return COORDINATOR_BOOTSTRAP_EXIT_CODE
}

/** `lib/<jar>` beneath the root; any other code source has no coordinator root. */
private fun coordinatorRoot(): Path? =
    try {
        val source =
            KastCoordinatorMain::class.java.protectionDomain.codeSource?.location?.toURI()?.let(Path::of)?.toRealPath()
        source?.takeIf { it.parent?.fileName?.toString() == "lib" }?.parent?.parent
    } catch (_: URISyntaxException) {
        null
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private val coordinatorJson = Json { encodeDefaults = true }

private const val COORDINATOR_USAGE_EXIT_CODE = 64

// `--product`, its path, and the action.
private const val COORDINATOR_ARGUMENT_COUNT = 3

// The public shell's BOOTSTRAP status: the frontend relays this failure unchanged.
private const val COORDINATOR_BOOTSTRAP_EXIT_CODE = 9
