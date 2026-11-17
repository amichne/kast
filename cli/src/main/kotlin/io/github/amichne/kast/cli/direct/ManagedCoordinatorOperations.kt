package io.github.amichne.kast.cli.direct

import io.github.amichne.kast.appserver.CoordinatorOperationExecutor
import io.github.amichne.kast.appserver.DaemonOperationCall
import io.github.amichne.kast.appserver.DaemonOperationClientRejection
import io.github.amichne.kast.appserver.DaemonOperationFailure
import io.github.amichne.kast.appserver.DaemonOperationProtocolFailure
import io.github.amichne.kast.appserver.DaemonOperationResult
import io.github.amichne.kast.appserver.WorkspaceOperationAdmission
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandCause
import io.github.amichne.kast.appserver.workspaceAdmission
import io.github.amichne.kast.distribution.cli.ContainerFailure
import io.github.amichne.kast.distribution.managed.CanonicalRoot
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
) : CoordinatorOperationExecutor, AutoCloseable {
    constructor(context: InstalledClientContext) : this(context.environment, InstalledWorkspaceAcquisition(context))

    private val monitor = Any()
    private val entries = mutableMapOf<CanonicalRoot, WorkspaceSlot>()
    private val preparationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var closed = false

    override suspend fun execute(root: CanonicalRoot, operation: DaemonOperationCall): DaemonOperationResult =
        execute(root, WorkspaceSettings.explicit(environment), operation)

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
            when (val selected = ticket.source.await()) {
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
        if (synchronized(monitor) { closed || slot.state !== ready }) return rejectedOperation(lifecycleTransition)
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
        revoke(slot, ready, failure)
        return result
    }

    private fun revoke(slot: WorkspaceSlot, ready: WorkspaceEntry.Ready, failure: DaemonOperationFailure) {
        val invalidated =
            synchronized(monitor) {
                if (slot.state !== ready) false
                else {
                    slot.state = WorkspaceEntry.Failed(failure)
                    true
                }
            }
        if (invalidated) ready.binding.close()
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
                is WorkspaceEntry.Available ->
                    if (entry.settings == settings.host) Refinement.Refined(WorkspaceTicket(slot, entry))
                    else Refinement.Rejected(configurationConflict)
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
        synchronized(monitor) {
            if (slot.state === preparing)
                slot.state =
                    when (result) {
                        is Refinement.Refined -> result.value
                        is Refinement.Rejected -> WorkspaceEntry.Failed(result.failure)
                    }
            preparing.result.complete(result)
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
