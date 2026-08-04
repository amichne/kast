package io.github.amichne.kast.idea.transition

@JvmInline
internal value class WorkspaceStateIdentity(val value: String) {
    init {
        require(value.isNotBlank()) { "Workspace state identity must not be blank" }
    }
}

@JvmInline
internal value class SemanticGeneration(val value: Long) {
    init {
        require(value > 0) { "Semantic generation must be positive" }
    }

    fun next(): SemanticGeneration = SemanticGeneration(Math.addExact(value, 1))
}

internal data class PublishedWorkspaceGeneration(
    val generation: SemanticGeneration,
    val identity: WorkspaceStateIdentity,
)

internal enum class WorkspaceLifecycle {
    Ready,
    Dirty,
    Settling,
    Refreshing,
    Reconciling,
    Verifying,
    Blocked,
}

internal enum class WorkspaceSignal {
    Source,
    BuildSemantic,
    Configuration,
    Scope,
    GitWorktree,
    RecoveryAudit,
}

internal enum class TransitionPhase {
    Settling,
    Refreshing,
    Reconciling,
    Verifying,
    Publishing,
}

internal data class TransitionBlocker(
    val phase: TransitionPhase,
    val detail: String,
) {
    init {
        require(detail.isNotBlank()) { "Transition blocker detail must not be blank" }
    }
}

internal data class WorkspaceTransitionSnapshot(
    val lifecycle: WorkspaceLifecycle,
    val pendingSignals: Set<WorkspaceSignal>,
    val published: PublishedWorkspaceGeneration?,
    val blocker: TransitionBlocker?,
    val observedEventCount: Long,
) {
    val isReady: Boolean
        get() = lifecycle == WorkspaceLifecycle.Ready && published != null
}

internal enum class TransitionRun {
    NoWork,
    Published,
    Invalidated,
    Blocked,
}

internal interface WorkspaceTransitionOperations {
    fun settle(signals: Set<WorkspaceSignal>)

    fun refresh(signals: Set<WorkspaceSignal>)

    fun captureIdentity(): WorkspaceStateIdentity

    fun reconcile(candidate: WorkspaceStateIdentity)

    /** Publishes the complete candidate generation in one durable transaction. */
    fun publish(generation: PublishedWorkspaceGeneration)
}

/**
 * Owns workspace freshness. Signals only invalidate and conflate work. Reconciliation and
 * identity verification are the only route to a published READY generation.
 */
internal class WorkspaceTransitionCoordinator(
    private val operations: WorkspaceTransitionOperations,
    initialPublished: PublishedWorkspaceGeneration? = null,
) {
    private val lock = Any()
    private val pendingSignals = linkedSetOf<WorkspaceSignal>()
    private var lifecycle = if (initialPublished == null) WorkspaceLifecycle.Dirty else WorkspaceLifecycle.Ready
    private var published = initialPublished
    private var blocker: TransitionBlocker? = null
    private var observedEventCount = 0L

    fun observe(signal: WorkspaceSignal) {
        synchronized(lock) {
            observedEventCount = Math.addExact(observedEventCount, 1)
            pendingSignals += signal
            blocker = null
            lifecycle = WorkspaceLifecycle.Dirty
        }
    }

    fun snapshot(): WorkspaceTransitionSnapshot = synchronized(lock) {
        WorkspaceTransitionSnapshot(
            lifecycle = lifecycle,
            pendingSignals = pendingSignals.toSet(),
            published = published,
            blocker = blocker,
            observedEventCount = observedEventCount,
        )
    }

    fun reconcilePending(): TransitionRun {
        val cycle = synchronized(lock) {
            if (pendingSignals.isEmpty()) return TransitionRun.NoWork
            val admittedSignals = pendingSignals.toSet()
            pendingSignals.clear()
            lifecycle = WorkspaceLifecycle.Settling
            TransitionCycle(admittedSignals, observedEventCount)
        }

        runPhase(TransitionPhase.Settling, WorkspaceLifecycle.Settling) {
            operations.settle(cycle.signals)
        } ?: return TransitionRun.Blocked
        runPhase(TransitionPhase.Refreshing, WorkspaceLifecycle.Refreshing) {
            operations.refresh(cycle.signals)
        } ?: return TransitionRun.Blocked

        val candidate = runPhase(TransitionPhase.Verifying, WorkspaceLifecycle.Reconciling) {
            operations.captureIdentity()
        } ?: return TransitionRun.Blocked
        runPhase(TransitionPhase.Reconciling, WorkspaceLifecycle.Reconciling) {
            operations.reconcile(candidate)
        } ?: return TransitionRun.Blocked
        val verified = runPhase(TransitionPhase.Verifying, WorkspaceLifecycle.Verifying) {
            operations.captureIdentity()
        } ?: return TransitionRun.Blocked

        return synchronized(lock) {
            if (
                candidate != verified ||
                observedEventCount != cycle.observedEventCount ||
                pendingSignals.isNotEmpty()
            ) {
                pendingSignals += WorkspaceSignal.RecoveryAudit
                lifecycle = WorkspaceLifecycle.Dirty
                return@synchronized TransitionRun.Invalidated
            }
            val next = PublishedWorkspaceGeneration(
                generation = published?.generation?.next() ?: SemanticGeneration(1),
                identity = verified,
            )
            try {
                operations.publish(next)
                published = next
                blocker = null
                lifecycle = WorkspaceLifecycle.Ready
                TransitionRun.Published
            } catch (failure: Throwable) {
                block(TransitionPhase.Publishing, failure)
                TransitionRun.Blocked
            }
        }
    }

    private fun <T> runPhase(
        phase: TransitionPhase,
        state: WorkspaceLifecycle,
        operation: () -> T,
    ): T? {
        synchronized(lock) { lifecycle = state }
        return try {
            operation()
        } catch (failure: Throwable) {
            synchronized(lock) { block(phase, failure) }
            null
        }
    }

    private fun block(phase: TransitionPhase, failure: Throwable) {
        blocker = TransitionBlocker(
            phase = phase,
            detail = failure.message?.takeIf(String::isNotBlank) ?: failure::class.qualifiedName.orEmpty(),
        )
        lifecycle = WorkspaceLifecycle.Blocked
    }

    private data class TransitionCycle(
        val signals: Set<WorkspaceSignal>,
        val observedEventCount: Long,
    )
}
