package io.github.amichne.kast.cli.direct

import io.github.amichne.kast.appserver.DaemonOperationFailure
import io.github.amichne.kast.distribution.cli.ContainerFailure
import io.github.amichne.kast.kernel.ElapsedTimeAllowance
import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
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

internal class WorkspaceSlot(var state: WorkspaceEntry) {
    val permit = Mutex()
}

internal data class WorkspaceTicket(val slot: WorkspaceSlot, val source: WorkspaceEntry.Available)

internal suspend fun WorkspaceTicket.await(
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

internal sealed interface DrainTicket {
    data class Own(val slot: WorkspaceSlot, val state: WorkspaceEntry.Draining) : DrainTicket

    data class Join(val state: WorkspaceEntry.Draining) : DrainTicket
}

internal sealed interface WorkspaceEntry {
    val phase: WorkspacePhase

    sealed interface Available : WorkspaceEntry {
        val settings: WorkspaceSettingsIdentity

        suspend fun await(): Refinement<Ready, DaemonOperationFailure>
    }

    data class Preparing(
        override val settings: WorkspaceSettingsIdentity,
        val result: CompletableDeferred<Refinement<Ready, DaemonOperationFailure>>,
    ) : Available {
        override val phase = WorkspacePhase.PREPARING

        override suspend fun await(): Refinement<Ready, DaemonOperationFailure> = result.await()
    }

    data class Ready(override val settings: WorkspaceSettingsIdentity, val binding: PreparedWorkspaceBinding) :
        Available {
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
