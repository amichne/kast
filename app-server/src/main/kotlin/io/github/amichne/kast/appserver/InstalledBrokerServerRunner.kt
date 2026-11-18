package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

/** The installed coordinator process: admits its configuration, starts, and serves until termination. */
class InstalledBrokerServerRunner
private constructor(
    private val kastExecutable: Path,
    private val userHome: Path,
    private val environment: Map<String, String>,
    private val semantic: CoordinatorSemantic?,
) : BrokerServerRunner {
    constructor(
        kastExecutable: Path,
        userHome: Path,
        environment: Map<String, String> = System.getenv(),
        semantic: CoordinatorOperationExecutor? = null,
    ) : this(kastExecutable, userHome, environment, semantic?.let { CoordinatorSemantic.LaunchBound(it) })

    /**
     * Each request's admitted runtime settings reach [requests], which owns conflicts with its bindings. [invocations]
     * additionally serves thin-frontend public shell invocations over the same executor.
     */
    constructor(
        kastExecutable: Path,
        userHome: Path,
        environment: Map<String, String>,
        requests: CoordinatorRequestExecutor,
        invocations: PublicInvocationExecutor? = null,
    ) : this(kastExecutable, userHome, environment, CoordinatorSemantic.RequestBound(requests, invocations))

    override fun serve(): BrokerServerRun {
        val options =
            when (val admission = InstalledCoordinatorConfiguration.admit(kastExecutable, userHome, environment)) {
                is Refinement.Refined -> admission.value
                is Refinement.Rejected -> return BrokerServerRun.Rejected(admission.failure.serverFailure())
            }
        return try {
            runBlocking {
                val running =
                    when (val started = InstalledCoordinator.start(options, DesktopDaemonDiscovery(), semantic)) {
                        is InstalledCoordinatorStart.Started -> started.coordinator
                        is InstalledCoordinatorStart.Rejected ->
                            return@runBlocking BrokerServerRun.Rejected(started.failure)
                    }
                val hook = Thread({ runBlocking { running.close() } }, "kast-coordinator-shutdown")
                Runtime.getRuntime().addShutdownHook(hook)
                try {
                    running.awaitTermination()
                    BrokerServerRun.Stopped
                } finally {
                    try {
                        Runtime.getRuntime().removeShutdownHook(hook)
                    } catch (_: IllegalStateException) {
                        /* Registered hook owns JVM shutdown. */
                    }
                    running.close()
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            BrokerServerRun.Rejected(BrokerServerFailure.INTERRUPTED)
        }
    }
}
