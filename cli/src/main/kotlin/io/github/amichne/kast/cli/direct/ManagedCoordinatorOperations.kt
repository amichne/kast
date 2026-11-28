package io.github.amichne.kast.cli.direct

import io.github.amichne.kast.appserver.CoordinatorOperationExecutor
import io.github.amichne.kast.appserver.CoordinatorOperationRequest
import io.github.amichne.kast.appserver.CoordinatorRequestExecutor
import io.github.amichne.kast.appserver.DaemonOperationCall
import io.github.amichne.kast.appserver.DaemonOperationClientRejection
import io.github.amichne.kast.appserver.DaemonOperationFailure
import io.github.amichne.kast.appserver.DaemonOperationProtocolFailure
import io.github.amichne.kast.appserver.DaemonOperationResult
import io.github.amichne.kast.appserver.WorkspaceOperationAdmission
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandCause
import io.github.amichne.kast.appserver.workspaceAdmission
import io.github.amichne.kast.distribution.cli.ControlStage
import io.github.amichne.kast.distribution.cli.ControlStageOutcome
import io.github.amichne.kast.distribution.cli.ControlStageTrace
import io.github.amichne.kast.distribution.cli.WorkspaceQueryEnvironment
import io.github.amichne.kast.distribution.contract.HostSettingsComparison
import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Each root has one retained binding and its own execution permit. Preparation belongs to the coordinator. */
internal class ManagedCoordinatorOperations(
    private val environment: Map<String, String>,
    private val acquisition: ManagedWorkspaceAcquisition,
) : CoordinatorOperationExecutor, CoordinatorRequestExecutor, AutoCloseable {
    constructor(context: InstalledClientContext) : this(context.environment, InstalledWorkspaceAcquisition(context))

    private val monitor = Any()
    private val entries = mutableMapOf<CanonicalRoot, WorkspaceSlot>()
    private val preparations = SupervisorJob()
    private val preparationScope = CoroutineScope(preparations + Dispatchers.IO)
    private val hostCleanup = SupervisorJob()
    private val hostCleanupScope = CoroutineScope(hostCleanup + Dispatchers.IO)
    private var closed = false

    override suspend fun execute(root: CanonicalRoot, operation: DaemonOperationCall): DaemonOperationResult =
        execute(root, WorkspaceSettings.explicit(environment), operation)

    override suspend fun execute(request: CoordinatorOperationRequest): DaemonOperationResult =
        execute(
            request.root,
            WorkspaceSettings.explicit(
                request.settings.environmentOver(environment),
                request.settings.gradleImportEnvironment,
            ),
            request.call,
        )

    suspend fun execute(
        root: CanonicalRoot,
        settings: WorkspaceSettings,
        operation: DaemonOperationCall,
    ): DaemonOperationResult {
        val request = settings.applyTo(environment)
        if (operation.workspaceAdmission() == WorkspaceOperationAdmission.EXISTING_AUTHORITY)
            return existing(root, request, operation)
        val result = preparedRead(root, settings, request, operation)
        return if (result.workspaceRejection()?.cause?.reprepares(operation) == true)
            preparedRead(root, settings, request, operation)
        else result
    }

    private suspend fun existing(
        root: CanonicalRoot,
        request: WorkspaceQueryEnvironment,
        operation: DaemonOperationCall,
    ): DaemonOperationResult {
        val slot = synchronized(monitor) { entries[root] }
        suspend fun read(): DaemonOperationResult {
            if (synchronized(monitor) { closed || entries[root] !== slot || slot?.state is WorkspaceEntry.Draining })
                return rejectedOperation(lifecycleTransition)
            val ready = synchronized(monitor) { slot?.state as? WorkspaceEntry.Ready }
            return runInterruptible(Dispatchers.IO) {
                acquisition.existing(root, request, operation, ready?.binding?.identity)
            }
        }
        return if (slot == null) read() else slot.permit.withLock { read() }
    }

    private suspend fun preparedRead(
        root: CanonicalRoot,
        settings: WorkspaceSettings,
        request: WorkspaceQueryEnvironment,
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
        request: WorkspaceQueryEnvironment,
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
                        acquisition.withRequestLease(root, request, ready.binding.identity) {
                            ready.binding.execute(root, operation)
                        }
                ) {
                    is Refinement.Rejected -> rejectedOperation(leased.failure)
                    is Refinement.Refined -> leased.value
                }
            }
        val failure = result.workspaceRejection() ?: return result
        if (!failure.cause.invalidatesBinding()) return result
        if (revoke(slot, ready, failure)) ready.binding.close()
        return result
    }

    private fun revoke(
        slot: WorkspaceSlot,
        ready: WorkspaceEntry.Ready,
        failure: DaemonOperationFailure,
        onRevoked: () -> Unit = {},
    ): Boolean =
        synchronized(monitor) {
            if (slot.state !== ready) false
            else {
                slot.state = WorkspaceEntry.Failed(failure)
                onRevoked()
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
                is WorkspaceEntry.Preparing -> joinPreparation(root, slot, entry, settings.host)
            }
        }

    private fun joinPreparation(
        root: CanonicalRoot,
        slot: WorkspaceSlot,
        entry: WorkspaceEntry.Preparing,
        requested: WorkspaceSettingsIdentity,
    ): Refinement<WorkspaceTicket, DaemonOperationFailure> {
        if (entry.settings == requested) {
            ControlStageTrace.record(
                ControlStage.WORKSPACE_PREPARATION_JOIN,
                ControlStageOutcome.REUSED,
                System.nanoTime(),
            )
            return Refinement.Refined(WorkspaceTicket(slot, entry))
        }
        val conflict =
            hostSettingsConflict(
                HostSettingsComparison.PREPARING,
                root,
                requested = requested.values,
                prepared = entry.settings.values,
                homeChanged = false,
                delegatedChanged = requested.delegated != entry.settings.delegated,
            )
        return Refinement.Rejected(DaemonOperationFailure.ManagedWorkspace(conflict))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun beginPreparation(
        root: CanonicalRoot,
        retainedSlot: WorkspaceSlot?,
        settings: WorkspaceSettings,
    ): WorkspaceTicket {
        val preparing = WorkspaceEntry.Preparing(settings.host, CompletableDeferred())
        val slot = retainedSlot ?: WorkspaceSlot(preparing).also { entries[root] = it }
        slot.state = preparing
        // An atomic start settles a job cancelled before dispatch. runInterruptible discards a value returned while
        // cancellation is pending, so the assignment keeps a returned binding for the drain that closes it.
        preparationScope.launch(start = CoroutineStart.ATOMIC) {
            var returned: Refinement<PreparedWorkspaceBinding, DaemonOperationFailure>? = null
            try {
                runInterruptible { returned = acquisition.prepare(root, settings.applyTo(environment, emptyMap())) }
            } finally {
                val prepared = returned?.takeIf { isActive || it is Refinement.Refined }
                finishPreparation(slot, preparing, prepared ?: Refinement.Rejected(lifecycleTransition))
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
            revoke(slot, ready, failure) {
                hostCleanupScope.launch {
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

    /**
     * Interrupts every preparation, which settles its Preparing result, and then drains each slot behind its permit.
     * Host-exit cleanup jobs are not cancelled, so each revoked binding still closes.
     */
    override fun close() {
        val interrupted = Thread.interrupted()
        try {
            val roots =
                synchronized(monitor) {
                    closed = true
                    entries.keys.toList()
                }
            preparations.cancel()
            runBlocking(NonCancellable) {
                roots.forEach { drain(it) }
                preparations.join()
                hostCleanup.complete()
                hostCleanup.join()
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}

private fun DaemonOperationResult.workspaceRejection(): DaemonOperationFailure.Workspace? {
    val rejection = (this as? DaemonOperationResult.Rejected)?.failure as? DaemonOperationClientRejection.Server
    return rejection?.failure as? DaemonOperationFailure.Workspace
}

private fun WorkspaceDemandCause.invalidatesBinding(): Boolean =
    this == hostUnavailable || this == WorkspaceDemandCause.HostChanged

/** A workspace wait sends no semantic operation, so a stale project target may be prepared once more. */
private fun WorkspaceDemandCause.reprepares(operation: DaemonOperationCall): Boolean =
    this == hostUnavailable ||
        (operation == DaemonOperationCall.PrepareWorkspace && this == WorkspaceDemandCause.HostChanged)

// A HostChanged result can also follow a readiness retry. Revoke its binding without replaying the operation.
private val hostUnavailable = WorkspaceDemandCause.Lifecycle(IdeLifecycleFailure.HOST_UNAVAILABLE)
private val lifecycleTransition = DaemonOperationFailure.Protocol(DaemonOperationProtocolFailure.LIFECYCLE_TRANSITION)
