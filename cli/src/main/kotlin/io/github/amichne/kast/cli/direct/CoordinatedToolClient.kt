package io.github.amichne.kast.cli.direct

import io.github.amichne.kast.appserver.DaemonOperationCall
import io.github.amichne.kast.appserver.DaemonOperationClient
import io.github.amichne.kast.appserver.DaemonOperationClientFailure
import io.github.amichne.kast.appserver.DaemonOperationClientRejection
import io.github.amichne.kast.appserver.DaemonOperationResult
import io.github.amichne.kast.appserver.InstalledSemanticCoordinator
import io.github.amichne.kast.appserver.InstalledSemanticService
import io.github.amichne.kast.appserver.PersistentBrokerServiceFailure
import io.github.amichne.kast.appserver.RequestedRuntimeSettings
import io.github.amichne.kast.distribution.cli.ControlStage
import io.github.amichne.kast.distribution.cli.ControlStageOutcome
import io.github.amichne.kast.distribution.cli.ControlStageTrace
import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The session retains one admitted peer. Each operation still checks the peer before it sends work. */
internal class CoordinatedToolClient(private val binding: InstalledClientBinding) : DaemonOperationClient {
    private val selected = AtomicReference<InstalledSemanticCoordinator?>()
    private val connection = Mutex()
    private val settings = RequestedRuntimeSettings.fromEnvironment(binding.environment)

    override fun read(root: CanonicalRoot, call: DaemonOperationCall): DaemonOperationResult =
        read(root, call, settings)

    fun read(
        root: CanonicalRoot,
        call: DaemonOperationCall,
        requestSettings: RequestedRuntimeSettings,
    ): DaemonOperationResult = runBlocking {
        if (binding.observe() != InstalledToolAdmission.AVAILABLE) return@runBlocking unavailable
        val started = System.nanoTime()
        val coordinator = selected.get() ?: connectOnce()
        ControlStageTrace.record(ControlStage.COORDINATOR_CONNECTION, outcome(coordinator == null), started)
        if (coordinator == null) return@runBlocking unavailable
        val invoking = System.nanoTime()
        val result = coordinator.execute(root, call, requestSettings)
        // No call retries the exchange. The next request can observe a newly available coordinator.
        if (result == unavailable) selected.compareAndSet(coordinator, null)
        ControlStageTrace.record(
            ControlStage.COORDINATED_INVOCATION,
            outcome(result is DaemonOperationResult.Rejected),
            invoking,
            generation = coordinator.generation,
            processId = coordinator.processId,
        )
        result
    }

    private suspend fun connectOnce(): InstalledSemanticCoordinator? = connection.withLock {
        selected.get() ?: connect().also(selected::set)
    }

    private suspend fun connect(): InstalledSemanticCoordinator? {
        val home =
            try {
                Path.of(binding.environment["HOME"] ?: return null).toRealPath()
            } catch (_: IOException) {
                return null
            }
        val result =
            InstalledSemanticCoordinator.connect(binding.installation, home) {
                runInterruptible(Dispatchers.IO) {
                    if (InstalledClientContext.verify(binding) is Refinement.Rejected)
                        Refinement.Rejected(PersistentBrokerServiceFailure.KAST_QUALIFICATION_REJECTED)
                    else
                        ControlStageTrace.observeRefinement(ControlStage.COORDINATOR_START) {
                            InstalledSemanticService.submit(binding.installation, home, binding.environment)
                        }
                }
            }
        return (result as? Refinement.Refined)?.value
    }
}

private fun outcome(rejected: Boolean): ControlStageOutcome =
    if (rejected) ControlStageOutcome.REJECTED else ControlStageOutcome.COMPLETED

private val unavailable =
    DaemonOperationResult.Rejected(DaemonOperationClientRejection.Transport(DaemonOperationClientFailure.UNAVAILABLE))
