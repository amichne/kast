package io.github.amichne.kast.idea

import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.PendingFileStage
import io.github.amichne.kast.indexstore.api.index.WorkspaceSourcePath

/**
 * Proof-carrying transition: `PendingFileStage -> WorkspaceIndexingActivity`.
 *
 * Derives only the constrained stage and workspace path needed to prove that
 * an indexing pass reached another admitted work item. Content, version, and
 * retry details remain owned by the durable file-stage protocol.
 */
@ConsistentCopyVisibility
internal data class WorkspaceIndexingActivity private constructor(
    val stage: FileIndexStage,
    val path: WorkspaceSourcePath,
) {
    companion object {
        /**
         * Proof transition: `PendingFileStage -> WorkspaceIndexingActivity`.
         *
         * Projects admitted durable work into the stable stage/path identity
         * needed by the progress clock; no raw path or stage name escapes.
         */
        fun derive(work: PendingFileStage): WorkspaceIndexingActivity = WorkspaceIndexingActivity(
            stage = work.stage,
            path = work.path,
        )
    }
}

internal sealed interface WorkspaceIndexingProgressObservation {
    data object Unobserved : WorkspaceIndexingProgressObservation

    @ConsistentCopyVisibility
    data class Observed private constructor(
        val activity: WorkspaceIndexingActivity,
        private val revision: WorkspaceIndexingProgressRevision,
    ) : WorkspaceIndexingProgressObservation {
        /**
         * Proof transition:
         * `(Observed, WorkspaceIndexingActivity) -> Observed`.
         *
         * Retains a distinct monotonic observation even when a retry reaches
         * the same constrained file-stage work again.
         */
        fun advance(activity: WorkspaceIndexingActivity): Observed = Observed(
            activity = activity,
            revision = revision.next(),
        )

        companion object {
            /** Proof transition: `WorkspaceIndexingActivity -> Observed`. */
            fun first(activity: WorkspaceIndexingActivity): Observed = Observed(
                activity = activity,
                revision = WorkspaceIndexingProgressRevision.first(),
            )
        }
    }

    companion object {
        /**
         * Proof transition:
         * `(WorkspaceIndexingProgressObservation, WorkspaceIndexingActivity) -> Observed`.
         *
         * Refines absent or prior progress into a monotonic observed state;
         * callers never manipulate raw counters or sentinel values.
         */
        fun advance(
            current: WorkspaceIndexingProgressObservation,
            activity: WorkspaceIndexingActivity,
        ): Observed = when (current) {
            Unobserved -> Observed.first(activity)
            is Observed -> current.advance(activity)
        }
    }
}

@JvmInline
private value class WorkspaceIndexingProgressRevision private constructor(
    private val value: Long,
) {
    /** Proof transition: `WorkspaceIndexingProgressRevision -> WorkspaceIndexingProgressRevision`. */
    fun next(): WorkspaceIndexingProgressRevision = WorkspaceIndexingProgressRevision(Math.addExact(value, 1L))

    companion object {
        /** Proof transition: `Unit -> WorkspaceIndexingProgressRevision`. */
        fun first(): WorkspaceIndexingProgressRevision = WorkspaceIndexingProgressRevision(1L)
    }
}

internal fun interface WorkspaceIndexingProgressSink {
    fun record(activity: WorkspaceIndexingActivity)
}

internal fun interface WorkspaceIndexingProgressProbe {
    fun observe(): WorkspaceIndexingProgressObservation
}

/** Retains typed indexing activity across concurrent worker and waiter threads. */
internal class WorkspaceIndexingProgressAuthority :
    WorkspaceIndexingProgressSink,
    WorkspaceIndexingProgressProbe {
    private val lock = Any()
    private var observation: WorkspaceIndexingProgressObservation =
        WorkspaceIndexingProgressObservation.Unobserved

    override fun record(activity: WorkspaceIndexingActivity) {
        synchronized(lock) {
            observation = WorkspaceIndexingProgressObservation.advance(observation, activity)
        }
    }

    override fun observe(): WorkspaceIndexingProgressObservation = synchronized(lock) { observation }
}

/**
 * Proof transition:
 * `(TransitionObservation, WorkspaceIndexingProgressObservation)`
 * `-> WorkspaceTransitionProgressObservation`.
 *
 * Combines lifecycle publication evidence with independent file-stage activity
 * so a long reconciliation can advance without manufacturing lifecycle events.
 */
@ConsistentCopyVisibility
internal data class WorkspaceTransitionProgressObservation private constructor(
    val transition: TransitionObservation,
    val indexing: WorkspaceIndexingProgressObservation,
) {
    companion object {
        /**
         * Proof transition:
         * `(TransitionObservation, WorkspaceIndexingProgressObservation)`
         * `-> WorkspaceTransitionProgressObservation`.
         */
        fun derive(
            transition: TransitionObservation,
            indexing: WorkspaceIndexingProgressObservation,
        ): WorkspaceTransitionProgressObservation = WorkspaceTransitionProgressObservation(transition, indexing)
    }
}
