package io.github.amichne.kast.cli.direct

import io.github.amichne.kast.appserver.AdmittedRuntimeSettings
import io.github.amichne.kast.appserver.CoordinatorOperationExecutor
import io.github.amichne.kast.appserver.CoordinatorOperationRequest
import io.github.amichne.kast.appserver.CoordinatorRequestExecutor
import io.github.amichne.kast.appserver.DaemonOperationCall
import io.github.amichne.kast.appserver.DaemonOperationClientRejection
import io.github.amichne.kast.appserver.DaemonOperationFailure
import io.github.amichne.kast.appserver.DaemonOperationInputFailure
import io.github.amichne.kast.appserver.DaemonOperationProtocolFailure
import io.github.amichne.kast.appserver.DaemonOperationResult
import io.github.amichne.kast.appserver.DaemonRuntimeSettingsFailure
import io.github.amichne.kast.appserver.WorkspaceOperationAdmission
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandCause
import io.github.amichne.kast.appserver.workspaceAdmission
import io.github.amichne.kast.distribution.cli.ContainerFailure
import io.github.amichne.kast.distribution.cli.ControlStage
import io.github.amichne.kast.distribution.cli.ControlStageOutcome
import io.github.amichne.kast.distribution.cli.ControlStageTrace
import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.kernel.ElapsedTimeAllowance
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class WorkspacePhase {
    PREPARING,
    READY,
    DRAINING,
    FAILED,
}

internal enum class WorkspaceDrain {
    DRAINED,
    NOT_REGISTERED,
}

/** Each root has one retained binding and its own execution permit. Preparation belongs to the coordinator. */
internal class ManagedCoordinatorOperations(
    private val environment: Map<String, String>,
    private val acquisition: ManagedWorkspaceAcquisition,
) : CoordinatorOperationExecutor, CoordinatorRequestExecutor, AutoCloseable {
    constructor(context: InstalledClientContext) : this(context.environment, InstalledWorkspaceAcquisition(context))

    private val monitor = Any()
    private val entries = mutableMapOf<CanonicalRoot, WorkspaceSlot>()
    private val preparationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var closed = false
    private val launchSettings = AdmittedRuntimeSettings.fromEnvironment(environment)

    override suspend fun execute(root: CanonicalRoot, operation: DaemonOperationCall): DaemonOperationResult =
        execute(root, WorkspaceSettings.explicit(environment), operation)

    override suspend fun execute(request: CoordinatorOperationRequest): DaemonOperationResult {
        val launch =
            when (val admitted = launchSettings) {
                is Refinement.Rejected -> return rejectRuntimeSettings(admitted.failure)
                is Refinement.Refined -> admitted.value
            }
        return when (val inherited = request.settings.environmentOver(environment, launch)) {
            is Refinement.Rejected -> rejectRuntimeSettings(inherited.failure)
            is Refinement.Refined -> execute(request.root, WorkspaceSettings.explicit(inherited.value), request.call)
        }
    }

    suspend fun execute(
        root: CanonicalRoot,
        settings: WorkspaceSettings,
        operation: DaemonOperationCall,
    ): DaemonOperationResult {
        val request = settings.applyTo(environment)
        if (operation.workspaceAdmission() == WorkspaceOperationAdmission.EXISTING_AUTHORITY)
            return existing(root, request, operation)
        val result = preparedRead(root, settings, request, operation)
        return if (result.hostUnavailable() != null) preparedRead(root, settings, request, operation) else result
    }

    private suspend fun existing(
        root: CanonicalRoot,
        request: Map<String, String>,
        operation: DaemonOperationCall,
    ): DaemonOperationResult {
        val slot = synchronized(monitor) { entries[root] }
        suspend fun read(): DaemonOperationResult {
            if (synchronized(monitor) { closed || entries[root] !== slot || slot?.state is WorkspaceEntry.Draining })
                return rejectedOperation(lifecycleTransition)
            return runInterruptible(Dispatchers.IO) { acquisition.existing(root, request, operation) }
        }
        return if (slot == null) read() else slot.permit.withLock { read() }
    }

    private suspend fun preparedRead(
        root: CanonicalRoot,
        settings: WorkspaceSettings,
        request: Map<String, String>,
        operation: DaemonOperationCall,
    ): DaemonOperationResult {
        val ticket =
            when (val admitted = prepareFor(root, settings)) {
                is Refinement.Rejected -> return rejectedOperation(admitted.failure)
                is Refinement.Refined -> admitted.value
            }
        val ready =
            when (val selected = ticket.await(settings)) {
                is Refinement.Rejected -> return rejectedOperation(selected.failure)
                is Refinement.Refined -> selected.value
            }
        return ticket.slot.permit.withLock { read(root, request, operation, ticket.slot, ready) }
    }

    private suspend fun read(
        root: CanonicalRoot,
        request: Map<String, String>,
        operation: DaemonOperationCall,
        slot: WorkspaceSlot,
        ready: WorkspaceEntry.Ready,
    ): DaemonOperationResult {
        val invalid =
            synchronized(monitor) {
                when {
                    closed -> lifecycleTransition
                    slot.state === ready -> null
                    else -> (slot.state as? WorkspaceEntry.Failed)?.reason ?: lifecycleTransition
                }
            }
        if (invalid != null) return rejectedOperation(invalid)
        val result =
            runInterruptible(Dispatchers.IO) {
                when (
                    val leased =
                        acquisition.withRequestLease(root, request) { observed ->
                            if (observed != ready.binding.identity) rejectedOperation(configurationConflict)
                            else ready.binding.read(root, operation)
                        }
                ) {
                    is Refinement.Rejected -> rejectedOperation(leased.failure)
                    is Refinement.Refined -> leased.value
                }
            }
        val failure = result.hostUnavailable() ?: return result
        if (revoke(slot, ready, failure)) ready.binding.close()
        return result
    }

    private fun revoke(slot: WorkspaceSlot, ready: WorkspaceEntry.Ready, failure: DaemonOperationFailure): Boolean =
        synchronized(monitor) {
            if (slot.state !== ready) false
            else {
                slot.state = WorkspaceEntry.Failed(failure)
                true
            }
        }

    private fun prepareFor(
        root: CanonicalRoot,
        settings: WorkspaceSettings,
    ): Refinement<WorkspaceTicket, DaemonOperationFailure> =
        synchronized(monitor) {
            if (closed) return@synchronized Refinement.Rejected(lifecycleTransition)
            val slot = entries[root] ?: return@synchronized Refinement.Refined(beginPreparation(root, null, settings))
            when (val entry = slot.state) {
                is WorkspaceEntry.Failed -> Refinement.Refined(beginPreparation(root, slot, settings))
                is WorkspaceEntry.Draining -> Refinement.Rejected(lifecycleTransition)
                is WorkspaceEntry.Ready -> Refinement.Refined(WorkspaceTicket(slot, entry))
                is WorkspaceEntry.Preparing ->
                    if (entry.settings == settings.host) {
                        ControlStageTrace.record(
                            ControlStage.WORKSPACE_PREPARATION_JOIN,
                            ControlStageOutcome.REUSED,
                            System.nanoTime(),
                        )
                        Refinement.Refined(WorkspaceTicket(slot, entry))
                    } else Refinement.Rejected(configurationConflict)
            }
        }

    private fun beginPreparation(
        root: CanonicalRoot,
        retainedSlot: WorkspaceSlot?,
        settings: WorkspaceSettings,
    ): WorkspaceTicket {
        val preparing = WorkspaceEntry.Preparing(settings.host, CompletableDeferred())
        val slot = retainedSlot ?: WorkspaceSlot(preparing).also { entries[root] = it }
        slot.state = preparing
        preparationScope.launch {
            try {
                val prepared = runInterruptible { acquisition.prepare(root, settings.applyTo(environment, emptyMap())) }
                finishPreparation(slot, preparing, prepared)
            } finally {
                if (!preparing.result.isCompleted)
                    finishPreparation(slot, preparing, Refinement.Rejected(lifecycleTransition))
            }
        }
        return WorkspaceTicket(slot, preparing)
    }

    private fun finishPreparation(
        slot: WorkspaceSlot,
        preparing: WorkspaceEntry.Preparing,
        prepared: Refinement<PreparedWorkspaceBinding, DaemonOperationFailure>,
    ) {
        val result =
            when (prepared) {
                is Refinement.Rejected -> prepared
                is Refinement.Refined -> Refinement.Refined(WorkspaceEntry.Ready(preparing.settings, prepared.value))
            }
        val ready = (result as? Refinement.Refined)?.value
        synchronized(monitor) {
            if (slot.state === preparing)
                slot.state =
                    when (result) {
                        is Refinement.Refined -> result.value
                        is Refinement.Rejected -> WorkspaceEntry.Failed(result.failure)
                    }
            ready?.let { watchHost(slot, it) }
            preparing.result.complete(result)
        }
    }

    private fun watchHost(slot: WorkspaceSlot, ready: WorkspaceEntry.Ready) {
        val authority = ready.binding.authority ?: return
        authority.host.exit.thenAccept { exit ->
            recordCoordinatorHostExit(exit)
            val failure =
                DaemonOperationFailure.Workspace(
                    authority.preparationId,
                    WorkspaceDemandCause.Lifecycle(IdeLifecycleFailure.HOST_UNAVAILABLE),
                )
            if (revoke(slot, ready, failure)) {
                preparationScope.launch {
                    slot.permit.withLock { runInterruptible { ready.binding.close() } }
                }
            }
        }
    }

    fun phase(root: CanonicalRoot): WorkspacePhase? = synchronized(monitor) { entries[root]?.state?.phase }

    suspend fun drain(root: CanonicalRoot): WorkspaceDrain {
        val ticket =
            synchronized(monitor) {
                val slot = entries[root] ?: return WorkspaceDrain.NOT_REGISTERED
                val state = slot.state
                if (state is WorkspaceEntry.Draining) DrainTicket.Join(state)
                else {
                    val draining = WorkspaceEntry.Draining(state, CompletableDeferred())
                    slot.state = draining
                    DrainTicket.Own(slot, draining)
                }
            }
        when (ticket) {
            is DrainTicket.Join -> ticket.state.finished.await()
            is DrainTicket.Own -> finishDrain(root, ticket)
        }
        return WorkspaceDrain.DRAINED
    }

    private suspend fun finishDrain(root: CanonicalRoot, ticket: DrainTicket.Own) =
        withContext(NonCancellable) {
            try {
                val binding = (ticket.state.prior as? WorkspaceEntry.Available)?.await()
                ticket.slot.permit.withLock {
                    if (binding is Refinement.Refined)
                        runInterruptible(Dispatchers.IO) { binding.value.binding.close() }
                }
            } finally {
                synchronized(monitor) { entries.remove(root, ticket.slot) }
                ticket.state.finished.complete(Unit)
            }
        }

    override fun close() {
        val interrupted = Thread.interrupted()
        try {
            val roots =
                synchronized(monitor) {
                    closed = true
                    entries.keys.toList()
                }
            runBlocking(NonCancellable) { roots.forEach { drain(it) } }
        } finally {
            preparationScope.cancel()
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}

private class WorkspaceSlot(var state: WorkspaceEntry) {
    val permit = Mutex()
}

private data class WorkspaceTicket(val slot: WorkspaceSlot, val source: WorkspaceEntry.Available)

private suspend fun WorkspaceTicket.await(
    settings: WorkspaceSettings
): Refinement<WorkspaceEntry.Ready, DaemonOperationFailure> {
    val allowance = settings.preparationWait ?: return source.await()
    return when (val parsed = ElapsedTimeAllowance.parseSetting(allowance)) {
        is Refinement.Rejected ->
            Refinement.Rejected(DaemonOperationFailure.ManagedWorkspace(ContainerFailure.INVALID_CONFIGURATION))
        is Refinement.Refined ->
            when (val budget = parsed.value) {
                ElapsedTimeAllowance.Unlimited -> source.await()
                is ElapsedTimeAllowance.Bounded ->
                    withTimeoutOrNull(budget.limit.value) { source.await() }
                        ?: Refinement.Rejected(
                            DaemonOperationFailure.ManagedWorkspace(ContainerFailure.PREPARATION_WAIT_EXPIRED)
                        )
            }
    }
}

private fun rejectRuntimeSettings(reason: DaemonRuntimeSettingsFailure): DaemonOperationResult =
    rejectedOperation(DaemonOperationFailure.Input(DaemonOperationInputFailure.RuntimeSettings(reason)))

private sealed interface DrainTicket {
    data class Own(val slot: WorkspaceSlot, val state: WorkspaceEntry.Draining) : DrainTicket

    data class Join(val state: WorkspaceEntry.Draining) : DrainTicket
}

private sealed interface WorkspaceEntry {
    val phase: WorkspacePhase

    sealed interface Available : WorkspaceEntry {
        val settings: Map<String, String>

        suspend fun await(): Refinement<Ready, DaemonOperationFailure>
    }

    data class Preparing(
        override val settings: Map<String, String>,
        val result: CompletableDeferred<Refinement<Ready, DaemonOperationFailure>>,
    ) : Available {
        override val phase = WorkspacePhase.PREPARING

        override suspend fun await(): Refinement<Ready, DaemonOperationFailure> = result.await()
    }

    data class Ready(override val settings: Map<String, String>, val binding: PreparedWorkspaceBinding) : Available {
        override val phase = WorkspacePhase.READY

        override suspend fun await(): Refinement<Ready, DaemonOperationFailure> = Refinement.Refined(this)
    }

    data class Draining(val prior: WorkspaceEntry, val finished: CompletableDeferred<Unit>) : WorkspaceEntry {
        override val phase = WorkspacePhase.DRAINING
    }

    data class Failed(val reason: DaemonOperationFailure) : WorkspaceEntry {
        override val phase = WorkspacePhase.FAILED
    }
}

/** Inspection rejected the retained Host before any semantic request reached it. */
private fun DaemonOperationResult.hostUnavailable(): DaemonOperationFailure.Workspace? {
    val rejection = (this as? DaemonOperationResult.Rejected)?.failure as? DaemonOperationClientRejection.Server
    val failure = rejection?.failure as? DaemonOperationFailure.Workspace
    return failure?.takeIf { it.cause == WorkspaceDemandCause.Lifecycle(IdeLifecycleFailure.HOST_UNAVAILABLE) }
}

private val lifecycleTransition = DaemonOperationFailure.Protocol(DaemonOperationProtocolFailure.LIFECYCLE_TRANSITION)
private val configurationConflict = DaemonOperationFailure.ManagedWorkspace(ContainerFailure.CONFIGURATION_CONFLICT)
