package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.AppServerAction
import io.github.amichne.kast.appserver.AppServerManagementRejection
import io.github.amichne.kast.appserver.AppServerManagementResult
import io.github.amichne.kast.appserver.BrokerServerFailure
import io.github.amichne.kast.appserver.BrokerServerRun
import io.github.amichne.kast.appserver.BrokerServerRunner
import io.github.amichne.kast.appserver.InstalledAppServerManager
import io.github.amichne.kast.appserver.InstalledBrokerServerRunner
import io.github.amichne.kast.appserver.PublicInvocations
import io.github.amichne.kast.cli.direct.InstalledClientContext
import io.github.amichne.kast.cli.direct.ManagedCoordinatorOperations
import io.github.amichne.kast.cli.publicshell.CoordinatorPublicInvocations
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Private launchd entry point; no command graph, host qualification or semantic dispatch runs here. */
object KastDaemonMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val environment = System.getenv()
        if (arguments.toList() == listOf("--semantic")) return presentDaemonRun(runSemanticDaemon(environment))
        if (arguments.isNotEmpty() && arguments.toList() != listOf("--login")) {
            System.err.println(Json.encodeToString(DaemonBootstrapRejection(BrokerServerFailure.ARGUMENTS_REJECTED)))
            exitProcess(DAEMON_REJECTED_EXIT_CODE)
        }
        val installed = installedKastExecutable()
        val outcome =
            when (installed) {
                is Refinement.Rejected ->
                    DaemonEntryOutcome.Ran(BrokerServerRun.Rejected(BrokerServerFailure.KAST_EXECUTABLE_REJECTED))
                is Refinement.Refined -> {
                    val home = Path.of(System.getProperty("user.home"))
                    runManagedDaemonBootstrap(
                        arguments.toList(),
                        environment,
                        InstalledBrokerServerRunner(installed.value, home, environment),
                    ) {
                        InstalledAppServerManager(installed.value, home, environment)
                            .execute(AppServerAction.Login, Path.of("").toAbsolutePath())
                    }
                }
            }
        when (outcome) {
            is DaemonEntryOutcome.LoginRejected -> {
                val failure =
                    when (val rejection = outcome.rejection) {
                        is AppServerManagementResult.Rejected ->
                            ServiceControlFailureDocument.Management(rejection.failure, rejection.serviceFailure)
                        is AppServerManagementResult.DaemonRejected ->
                            ServiceControlFailureDocument.Daemon(rejection.reason)
                    }
                System.err.println(serviceControlJson.encodeToString<ServiceControlFailureDocument>(failure))
                exitProcess(DAEMON_REJECTED_EXIT_CODE)
            }
            is DaemonEntryOutcome.Ran -> presentDaemonRun(outcome.run)
        }
    }
}

internal fun presentDaemonRun(result: BrokerServerRun) {
    if (result is BrokerServerRun.Rejected) {
        System.err.println(Json.encodeToString(DaemonBootstrapRejection(result.failure)))
        exitProcess(DAEMON_REJECTED_EXIT_CODE)
    }
}

private fun runSemanticDaemon(environment: Map<String, String>): BrokerServerRun =
    when (val admitted = InstalledClientContext.current(environment)) {
        is Refinement.Rejected -> BrokerServerRun.Rejected(BrokerServerFailure.KAST_EXECUTABLE_REJECTED)
        is Refinement.Refined -> runSemanticCoordinator(admitted.value, admitted.value.installation, environment)
    }

/**
 * The one semantic coordinator composition. [root] owns the coordinator's state and publication; [context] is the
 * installation whose Hosts it manages. The native daemon passes the same path for both.
 */
internal fun runSemanticCoordinator(
    context: InstalledClientContext,
    root: Path,
    environment: Map<String, String>,
): BrokerServerRun {
    val home = Path.of(System.getProperty("user.home")).toRealPath()
    val semantic =
        environment +
            mapOf(
                "KAST_APP_SERVER_PUBLIC_ENDPOINT" to "private",
                "CODEX_HOME" to home.resolve(".codex").toString(),
            )
    return ManagedCoordinatorOperations(context).use {
        runDaemonBootstrap(
            emptyList(),
            semantic,
            InstalledBrokerServerRunner(
                root.resolve("bin/kast"),
                home,
                semantic,
                requests = it,
                invocations = PublicInvocations(CoordinatorPublicInvocations(context.installation)),
            ),
        )
    }
}

internal sealed interface DaemonEntryOutcome {
    data class Ran(val run: BrokerServerRun) : DaemonEntryOutcome

    data class LoginRejected(val rejection: AppServerManagementRejection) : DaemonEntryOutcome
}

internal fun runManagedDaemonBootstrap(
    arguments: List<String>,
    environment: Map<String, String>,
    runner: BrokerServerRunner,
    login: () -> AppServerManagementResult,
): DaemonEntryOutcome {
    if (arguments == listOf("--login")) {
        daemonIngressFailure(emptyList(), environment)?.let { failure ->
            return DaemonEntryOutcome.Ran(BrokerServerRun.Rejected(failure))
        }
        when (val admitted = login()) {
            is AppServerManagementResult.Completed -> Unit
            is AppServerManagementRejection -> return DaemonEntryOutcome.LoginRejected(admitted)
        }
        return DaemonEntryOutcome.Ran(runDaemonBootstrap(emptyList(), environment, runner))
    }
    return DaemonEntryOutcome.Ran(runDaemonBootstrap(arguments, environment, runner))
}

/** Managed environment presence is an ingress check; the coordinator proves its exact ownership and readiness. */
internal fun runDaemonBootstrap(
    arguments: List<String>,
    environment: Map<String, String>,
    runner: BrokerServerRunner,
): BrokerServerRun =
    daemonIngressFailure(arguments, environment)?.let { BrokerServerRun.Rejected(it) } ?: runner.serve()

private fun daemonIngressFailure(arguments: List<String>, environment: Map<String, String>): BrokerServerFailure? =
    when {
        arguments.isNotEmpty() -> BrokerServerFailure.ARGUMENTS_REJECTED
        environment.containsKey("KAST_SAVED_CONFIGURATION_FAILURE") -> BrokerServerFailure.CONFIGURATION_REJECTED
        environment["BROKER_SERVICE_IDENTITY"].isNullOrBlank() ||
            environment["BROKER_READINESS_FILE"].isNullOrBlank() -> BrokerServerFailure.READINESS_REJECTED
        else -> null
    }

@Serializable private data class DaemonBootstrapRejection(val failure: BrokerServerFailure)

private const val DAEMON_REJECTED_EXIT_CODE = 64
