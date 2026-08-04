package io.github.amichne.kast.idea.transition

import com.intellij.openapi.progress.ProcessCanceledException
import java.util.concurrent.CancellationException

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

internal enum class GenerationPublication {
    Published,
    Invalidated,
}

internal interface WorkspaceTransitionOperations {
    fun settle(signals: Set<WorkspaceSignal>)

    fun refresh(signals: Set<WorkspaceSignal>)

    fun captureIdentity(): WorkspaceStateIdentity

    /** Returns the identity of the inputs that were actually reconciled. */
    fun reconcile(candidate: WorkspaceStateIdentity): WorkspaceStateIdentity

    /** Publishes the complete candidate generation in one durable transaction. */
    fun publish(generation: PublishedWorkspaceGeneration): GenerationPublication
}

/**
 * Owns workspace freshness. Signals only invalidate and conflate work. Reconciliation and
 * identity verification are the only route to a published READY generation.
 */
internal class WorkspaceTransitionCoordinator(
    private val operations: WorkspaceTransitionOperations,
    initialPublished: PublishedWorkspaceGeneration? = null,
    private val onTransition: (WorkspaceTransitionSnapshot) -> Unit = {},
    private val onBlocked: (TransitionBlocker, Throwable) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private val pendingSignals = linkedSetOf<WorkspaceSignal>()
    private var lifecycle = if (initialPublished == null) WorkspaceLifecycle.Dirty else WorkspaceLifecycle.Ready
    private var published = initialPublished
    private var blocker: TransitionBlocker? = null
    private var observedEventCount = 0L

    fun observe(signal: WorkspaceSignal) {
        val changed = synchronized(lock) {
            observedEventCount = Math.addExact(observedEventCount, 1)
            pendingSignals += signal
            blocker = null
            if (lifecycle != WorkspaceLifecycle.Settling) lifecycle = WorkspaceLifecycle.Dirty
            snapshotLocked()
        }
        emit(changed)
    }

    fun snapshot(): WorkspaceTransitionSnapshot = synchronized(lock, ::snapshotLocked)

    fun reconcilePending(): TransitionRun {
        val settling = synchronized(lock) {
            if (pendingSignals.isEmpty()) return TransitionRun.NoWork
            lifecycle = WorkspaceLifecycle.Settling
            pendingSignals.toSet() to snapshotLocked()
        }
        emit(settling.second)
        runPhase(TransitionPhase.Settling) { operations.settle(settling.first) }
            .onFailure { return block(TransitionPhase.Settling, it) }

        val cycleAndState = synchronized(lock) {
            val cycle = TransitionCycle(
                signals = pendingSignals.toSet(),
                observedEventCount = observedEventCount,
            )
            pendingSignals.clear()
            lifecycle = WorkspaceLifecycle.Refreshing
            cycle to snapshotLocked()
        }
        val cycle = cycleAndState.first
        emit(cycleAndState.second)

        runPhase(TransitionPhase.Refreshing) { operations.refresh(cycle.signals) }
            .onFailure { return block(TransitionPhase.Refreshing, it, cycle) }
        if (!advance(cycle, WorkspaceLifecycle.Reconciling)) return TransitionRun.Invalidated

        val candidate = runPhase(TransitionPhase.Reconciling, operations::captureIdentity)
            .getOrElse { return block(TransitionPhase.Reconciling, it, cycle) }
        val reconciledCandidate = runPhase(TransitionPhase.Reconciling) { operations.reconcile(candidate) }
            .getOrElse { return block(TransitionPhase.Reconciling, it, cycle) }
        if (!advance(cycle, WorkspaceLifecycle.Verifying)) return TransitionRun.Invalidated

        val verified = runPhase(TransitionPhase.Verifying, operations::captureIdentity)
            .getOrElse { return block(TransitionPhase.Verifying, it, cycle) }
        if (reconciledCandidate != verified) {
            invalidate(cycle, includeAudit = true)
            return TransitionRun.Invalidated
        }

        return publish(cycle, verified)
    }

    private fun publish(
        cycle: TransitionCycle,
        verified: WorkspaceStateIdentity,
    ): TransitionRun {
        var failure: Throwable? = null
        var failedBlocker: TransitionBlocker? = null
        val resultAndState = synchronized(lock) {
            if (!isCurrent(cycle)) {
                retainForRetry(cycle, includeAudit = false)
                return@synchronized TransitionRun.Invalidated to snapshotLocked()
            }
            val next = PublishedWorkspaceGeneration(
                generation = published?.generation?.next() ?: SemanticGeneration(1),
                identity = verified,
            )
            val result = try {
                when (operations.publish(next)) {
                    GenerationPublication.Published -> {
                        if (isCurrent(cycle)) {
                            published = next
                            blocker = null
                            lifecycle = WorkspaceLifecycle.Ready
                            TransitionRun.Published
                        } else {
                            retainForRetry(cycle, includeAudit = false)
                            TransitionRun.Invalidated
                        }
                    }

                    GenerationPublication.Invalidated -> {
                        retainForRetry(cycle, includeAudit = false)
                        TransitionRun.Invalidated
                    }
                }
            } catch (caught: Throwable) {
                rethrowCancellation(caught)
                retainForRetry(cycle, includeAudit = false)
                failure = caught
                failedBlocker = blockLocked(TransitionPhase.Publishing, caught)
                TransitionRun.Blocked
            }
            result to snapshotLocked()
        }
        emit(resultAndState.second)
        failure?.let { caught -> notifyBlocked(checkNotNull(failedBlocker), caught) }
        return resultAndState.first
    }

    private fun advance(
        cycle: TransitionCycle,
        next: WorkspaceLifecycle,
    ): Boolean {
        val currentAndState = synchronized(lock) {
            val current = isCurrent(cycle)
            if (current) {
                lifecycle = next
            } else {
                retainForRetry(cycle, includeAudit = false)
            }
            current to snapshotLocked()
        }
        emit(currentAndState.second)
        return currentAndState.first
    }

    private fun invalidate(cycle: TransitionCycle, includeAudit: Boolean) {
        val changed = synchronized(lock) {
            retainForRetry(cycle, includeAudit)
            snapshotLocked()
        }
        emit(changed)
    }

    private fun block(
        phase: TransitionPhase,
        failure: Throwable,
        cycle: TransitionCycle? = null,
    ): TransitionRun {
        rethrowCancellation(failure)
        val blockerAndState = synchronized(lock) {
            cycle?.let { pendingSignals += it.signals }
            val currentBlocker = blockLocked(phase, failure)
            currentBlocker to snapshotLocked()
        }
        emit(blockerAndState.second)
        notifyBlocked(blockerAndState.first, failure)
        return TransitionRun.Blocked
    }

    private fun blockLocked(phase: TransitionPhase, failure: Throwable): TransitionBlocker {
        val currentBlocker = TransitionBlocker(
            phase = phase,
            detail = failure.message?.takeIf(String::isNotBlank) ?: failure::class.qualifiedName.orEmpty(),
        )
        blocker = currentBlocker
        lifecycle = WorkspaceLifecycle.Blocked
        return currentBlocker
    }

    private fun retainForRetry(cycle: TransitionCycle, includeAudit: Boolean) {
        pendingSignals += cycle.signals
        if (includeAudit) pendingSignals += WorkspaceSignal.RecoveryAudit
        lifecycle = WorkspaceLifecycle.Dirty
    }

    private fun isCurrent(cycle: TransitionCycle): Boolean =
        observedEventCount == cycle.observedEventCount && pendingSignals.isEmpty()

    private fun snapshotLocked(): WorkspaceTransitionSnapshot = WorkspaceTransitionSnapshot(
        lifecycle = lifecycle,
        pendingSignals = pendingSignals.toSet(),
        published = published,
        blocker = blocker,
        observedEventCount = observedEventCount,
    )

    private fun emit(snapshot: WorkspaceTransitionSnapshot) {
        runCatching { onTransition(snapshot) }
    }

    private fun notifyBlocked(blocker: TransitionBlocker, failure: Throwable) {
        runCatching { onBlocked(blocker, failure) }
    }

    private fun <T> runPhase(phase: TransitionPhase, operation: () -> T): Result<T> = try {
        Result.success(operation())
    } catch (failure: Throwable) {
        rethrowCancellation(failure)
        Result.failure(failure)
    }

    private fun rethrowCancellation(failure: Throwable) {
        when (failure) {
            is InterruptedException -> {
                Thread.currentThread().interrupt()
                throw failure
            }

            is CancellationException,
            is ProcessCanceledException,
            -> throw failure
        }
    }

    private data class TransitionCycle(
        val signals: Set<WorkspaceSignal>,
        val observedEventCount: Long,
    )
}
