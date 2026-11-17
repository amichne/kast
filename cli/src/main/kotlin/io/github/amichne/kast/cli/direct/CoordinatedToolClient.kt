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

    override fun read(root: CanonicalRoot, call: DaemonOperationCall): DaemonOperationResult =
        exchange(root, call) {
            RequestedRuntimeSettings.fromWorkspace(binding.installation, root.path, binding.environment)
        }

    fun read(
        root: CanonicalRoot,
        call: DaemonOperationCall,
        requestSettings: RequestedRuntimeSettings,
    ): DaemonOperationResult = exchange(root, call) { requestSettings }

    private fun exchange(
        root: CanonicalRoot,
        call: DaemonOperationCall,
        projectSettings: () -> RequestedRuntimeSettings,
    ): DaemonOperationResult = runBlocking {
        if (binding.observe() != InstalledToolAdmission.AVAILABLE) return@runBlocking unavailable
        val requestSettings = projectSettings()
        val started = System.nanoTime()
        val connected = selected.get()?.let { Refinement.Refined(it) } ?: connectOnce()
        ControlStageTrace.record(
            ControlStage.COORDINATOR_CONNECTION,
            outcome(connected is Refinement.Rejected),
            started,
        )
        val coordinator =
            when (connected) {
                is Refinement.Refined -> connected.value
                is Refinement.Rejected -> return@runBlocking DaemonOperationResult.Rejected(connected.failure)
            }
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

    private suspend fun connectOnce(): Refinement<InstalledSemanticCoordinator, DaemonOperationClientRejection> =
        connection.withLock {
            selected.get()?.let { Refinement.Refined(it) }
                ?: connect().also { if (it is Refinement.Refined) selected.set(it.value) }
        }

    private suspend fun connect(): Refinement<InstalledSemanticCoordinator, DaemonOperationClientRejection> {
        val home =
            try {
                Path.of(binding.environment["HOME"] ?: return Refinement.Rejected(unavailable.failure)).toRealPath()
            } catch (_: IOException) {
                return Refinement.Rejected(unavailable.failure)
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
        return when (result) {
            is Refinement.Refined -> result
            is Refinement.Rejected -> Refinement.Rejected(DaemonOperationClientRejection.Startup(result.failure))
        }
    }
}

private fun outcome(rejected: Boolean): ControlStageOutcome =
    if (rejected) ControlStageOutcome.REJECTED else ControlStageOutcome.COMPLETED

private val unavailable =
    DaemonOperationResult.Rejected(DaemonOperationClientRejection.Transport(DaemonOperationClientFailure.UNAVAILABLE))
