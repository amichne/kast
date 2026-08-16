package io.github.amichne.kast.workspace.service

import io.github.amichne.kast.evidence.contract.OpenCanonicalWorkspacePublication
import io.github.amichne.kast.evidence.contract.PreparedCanonicalWorkspacePublication
import io.github.amichne.kast.evidence.contract.WorkspacePublicationDiscard
import io.github.amichne.kast.evidence.contract.WorkspacePublicationOpening
import io.github.amichne.kast.evidence.contract.WorkspacePublicationPreparation
import io.github.amichne.kast.evidence.contract.WorkspacePublicationResult
import io.github.amichne.kast.evidence.contract.WorkspacePublicationTransaction
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.WorkspaceCandidateCapture
import io.github.amichne.kast.workspace.contract.WorkspaceCandidateReconciliation
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceInvalidationSink
import io.github.amichne.kast.workspace.contract.WorkspacePublicationBlocker
import io.github.amichne.kast.workspace.contract.WorkspacePublicationRun
import io.github.amichne.kast.workspace.contract.WorkspaceReconciliationPort
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import io.github.amichne.kast.workspace.contract.WorkspaceSignal

/**
 * Single event-driven owner of canonical workspace reconciliation and publication.
 *
 * Candidate work occurs outside the state lock. The final currency check and persistence
 * transaction share one critical section, so observers see either the withdrawn reconciliation
 * state or the complete new [WorkspaceRuntimeState.Ready] value, never a mixed publication.
 */
class WorkspacePublicationCoordinator(
    private val reconciliation: WorkspaceReconciliationPort,
    private val publication: WorkspacePublicationTransaction,
) : WorkspaceInspectionOperations, WorkspaceInvalidationSink {
    private val lock = Any()
    private val pendingSignals = linkedSetOf(WorkspaceSignal.InitialProjectModel)
    private var observedRevision = WorkspaceEventRevision.initial()
    private var runtimeState: WorkspaceRuntimeState = WorkspaceRuntimeState.Starting

    override fun inspect(): WorkspaceRuntimeState = synchronized(lock) { runtimeState }

    override fun observe(signal: WorkspaceSignal) {
        synchronized(lock) {
            observedRevision = observedRevision.next()
            pendingSignals += signal
            runtimeState = when (runtimeState) {
                WorkspaceRuntimeState.Absent -> WorkspaceRuntimeState.Absent
                WorkspaceRuntimeState.Stopping -> WorkspaceRuntimeState.Stopping
                WorkspaceRuntimeState.Starting,
                WorkspaceRuntimeState.Reconciling,
                is WorkspaceRuntimeState.Ready,
                is WorkspaceRuntimeState.Blocked,
                    -> WorkspaceRuntimeState.Reconciling
            }
        }
    }

    /**
     * Transition: pending workspace events to one atomic publication or a closed terminal result.
     *
     * The method captures before and after reconciliation. Any event or candidate movement
     * invalidates the pass before publication, while expected adapter and storage failures become
     * finite [WorkspacePublicationRun.Blocked] data.
     */
    fun reconcile(): WorkspacePublicationRun {
        val cycle = when (val beginning = beginCycle()) {
            CycleBeginning.NoWork -> return WorkspacePublicationRun.NoWork
            is CycleBeginning.Started -> beginning.cycle
        }
        val before = when (val captured = reconciliation.capture(cycle.signals)) {
            is WorkspaceCandidateCapture.Captured -> captured.candidate
            is WorkspaceCandidateCapture.Rejected -> return block(cycle, captured.blocker)
        }
        if (currency(cycle) == CycleCurrency.Invalidated) {
            return WorkspacePublicationRun.Invalidated
        }
        val open = when (val opening = publication.begin()) {
            is WorkspacePublicationOpening.Opened -> opening.publication
            is WorkspacePublicationOpening.Rejected -> return block(
                cycle,
                WorkspacePublicationBlocker.PublicationUnavailable,
            )
        }
        val reconciled = when (val result = reconciliation.reconcile(before)) {
            is WorkspaceCandidateReconciliation.Reconciled -> result.workspace
            is WorkspaceCandidateReconciliation.Rejected -> {
                return discardThenBlock(open, cycle, result.blocker)
            }
        }
        if (reconciled.candidate != before) return discardThenMove(open)
        if (currency(cycle) == CycleCurrency.Invalidated) {
            return discardThenInvalidate(open)
        }
        val after = when (val captured = reconciliation.capture(cycle.signals)) {
            is WorkspaceCandidateCapture.Captured -> captured.candidate
            is WorkspaceCandidateCapture.Rejected -> {
                return discardThenBlock(open, cycle, captured.blocker)
            }
        }
        if (before != after) return discardThenMove(open)
        val prepared = when (val preparation = publication.prepare(open, reconciled)) {
            is WorkspacePublicationPreparation.Prepared -> preparation.publication
            is WorkspacePublicationPreparation.Rejected -> {
                return discardThenBlock(
                    open,
                    cycle,
                    WorkspacePublicationBlocker.PublicationUnavailable,
                )
            }
        }
        return commitIfCurrent(cycle, prepared)
    }

    private fun beginCycle(): CycleBeginning = synchronized(lock) {
        when (runtimeState) {
            WorkspaceRuntimeState.Absent,
            WorkspaceRuntimeState.Stopping,
            is WorkspaceRuntimeState.Ready,
                -> CycleBeginning.NoWork
            WorkspaceRuntimeState.Starting,
            WorkspaceRuntimeState.Reconciling,
            is WorkspaceRuntimeState.Blocked,
                -> {
                    val cycle = PublicationCycle(
                        revision = observedRevision,
                        signals = pendingSignals.toSet(),
                    )
                    pendingSignals.clear()
                    runtimeState = WorkspaceRuntimeState.Reconciling
                    CycleBeginning.Started(cycle)
                }
        }
    }

    private fun commitIfCurrent(
        cycle: PublicationCycle,
        prepared: PreparedCanonicalWorkspacePublication,
    ): WorkspacePublicationRun = synchronized(lock) {
        if (currencyLocked(cycle) == CycleCurrency.Invalidated) {
            return@synchronized discardPreparedThen(
                prepared,
                WorkspacePublicationRun.Invalidated,
            )
        }
        val result = publication.commit(prepared)
        if (currencyLocked(cycle) == CycleCurrency.Invalidated) {
            return@synchronized discardPreparedThen(
                prepared,
                WorkspacePublicationRun.Invalidated,
            )
        }
        when (result) {
            is WorkspacePublicationResult.Published -> {
                runtimeState = WorkspaceRuntimeState.Ready(result.workspace)
                WorkspacePublicationRun.Published(result.workspace)
            }
            is WorkspacePublicationResult.Rejected -> {
                val blocker = WorkspacePublicationBlocker.PublicationUnavailable
                discardPreparedThen(prepared, WorkspacePublicationRun.Blocked(blocker))
            }
        }
    }

    private fun discardThenBlock(
        open: OpenCanonicalWorkspacePublication,
        cycle: PublicationCycle,
        blocker: WorkspacePublicationBlocker,
    ): WorkspacePublicationRun = when (publication.discard(open)) {
        WorkspacePublicationDiscard.Discarded -> block(cycle, blocker)
        is WorkspacePublicationDiscard.Rejected -> forcePublicationBlock()
    }

    private fun discardThenMove(
        open: OpenCanonicalWorkspacePublication,
    ): WorkspacePublicationRun = when (publication.discard(open)) {
        WorkspacePublicationDiscard.Discarded -> moved()
        is WorkspacePublicationDiscard.Rejected -> forcePublicationBlock()
    }

    private fun discardThenInvalidate(
        open: OpenCanonicalWorkspacePublication,
    ): WorkspacePublicationRun = when (publication.discard(open)) {
        WorkspacePublicationDiscard.Discarded -> WorkspacePublicationRun.Invalidated
        is WorkspacePublicationDiscard.Rejected -> forcePublicationBlock()
    }

    private fun discardPreparedThen(
        prepared: PreparedCanonicalWorkspacePublication,
        success: WorkspacePublicationRun,
    ): WorkspacePublicationRun = when (publication.discard(prepared)) {
        WorkspacePublicationDiscard.Discarded -> {
            if (success is WorkspacePublicationRun.Blocked) {
                runtimeState = WorkspaceRuntimeState.Blocked(success.blocker)
            }
            success
        }
        is WorkspacePublicationDiscard.Rejected -> forcePublicationBlockLocked()
    }

    private fun forcePublicationBlock(): WorkspacePublicationRun = synchronized(lock) {
        forcePublicationBlockLocked()
    }

    private fun forcePublicationBlockLocked(): WorkspacePublicationRun.Blocked {
        val blocker = WorkspacePublicationBlocker.PublicationUnavailable
        runtimeState = WorkspaceRuntimeState.Blocked(blocker)
        return WorkspacePublicationRun.Blocked(blocker)
    }

    private fun block(
        cycle: PublicationCycle,
        blocker: WorkspacePublicationBlocker,
    ): WorkspacePublicationRun = synchronized(lock) {
        when (currencyLocked(cycle)) {
            CycleCurrency.Current -> {
                runtimeState = WorkspaceRuntimeState.Blocked(blocker)
                WorkspacePublicationRun.Blocked(blocker)
            }
            CycleCurrency.Invalidated -> WorkspacePublicationRun.Invalidated
        }
    }

    private fun moved(): WorkspacePublicationRun = synchronized(lock, ::movedLocked)

    private fun movedLocked(): WorkspacePublicationRun {
        observedRevision = observedRevision.next()
        pendingSignals += WorkspaceSignal.RecoveryAudit
        runtimeState = WorkspaceRuntimeState.Reconciling
        return WorkspacePublicationRun.Invalidated
    }

    private fun currency(cycle: PublicationCycle): CycleCurrency = synchronized(lock) {
        currencyLocked(cycle)
    }

    private fun currencyLocked(cycle: PublicationCycle): CycleCurrency =
        if (observedRevision == cycle.revision && pendingSignals.isEmpty()) {
            CycleCurrency.Current
        } else {
            runtimeState = WorkspaceRuntimeState.Reconciling
            CycleCurrency.Invalidated
        }
}

private data class PublicationCycle(
    val revision: WorkspaceEventRevision,
    val signals: Set<WorkspaceSignal>,
)

@JvmInline
private value class WorkspaceEventRevision private constructor(
    private val value: Long,
) {
    fun next(): WorkspaceEventRevision = WorkspaceEventRevision(Math.addExact(value, 1L))

    companion object {
        fun initial(): WorkspaceEventRevision = WorkspaceEventRevision(0L)
    }
}

private sealed interface CycleBeginning {
    data object NoWork : CycleBeginning

    data class Started(
        val cycle: PublicationCycle,
    ) : CycleBeginning
}

private enum class CycleCurrency {
    Current,
    Invalidated,
}
