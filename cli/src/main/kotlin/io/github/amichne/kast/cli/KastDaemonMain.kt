package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.BrokerServerFailure
import io.github.amichne.kast.appserver.BrokerServerRun
import io.github.amichne.kast.appserver.BrokerServerRunner
import io.github.amichne.kast.appserver.InstalledBrokerServerRunner
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
        val result =
            runDaemonBootstrap(arguments.toList(), environment) {
                when (val installed = installedKastExecutable()) {
                    is Refinement.Refined ->
                        InstalledBrokerServerRunner(
                                installed.value,
                                Path.of(System.getProperty("user.home")),
                                environment,
                            )
                            .serve()
                    is Refinement.Rejected -> BrokerServerRun.Rejected(BrokerServerFailure.KAST_EXECUTABLE_REJECTED)
                }
            }
        when (result) {
            BrokerServerRun.Stopped -> Unit
            is BrokerServerRun.Rejected -> {
                System.err.println(Json.encodeToString(DaemonBootstrapRejection(result.failure)))
                exitProcess(DAEMON_REJECTED_EXIT_CODE)
            }
        }
    }
}

/** Managed environment presence is an ingress check; the coordinator proves its exact ownership and readiness. */
internal fun runDaemonBootstrap(
    arguments: List<String>,
    environment: Map<String, String>,
    runner: BrokerServerRunner,
): BrokerServerRun =
    when {
        arguments.isNotEmpty() -> BrokerServerRun.Rejected(BrokerServerFailure.ARGUMENTS_REJECTED)
        environment.containsKey("KAST_SAVED_CONFIGURATION_FAILURE") ->
            BrokerServerRun.Rejected(BrokerServerFailure.CONFIGURATION_REJECTED)
        environment["BROKER_SERVICE_IDENTITY"].isNullOrBlank() ||
            environment["BROKER_READINESS_FILE"].isNullOrBlank() ->
            BrokerServerRun.Rejected(BrokerServerFailure.READINESS_REJECTED)
        else -> runner.serve()
    }

@Serializable private data class DaemonBootstrapRejection(val failure: BrokerServerFailure)

private const val DAEMON_REJECTED_EXIT_CODE = 64
