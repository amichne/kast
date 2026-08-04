package io.github.amichne.kast.idea.transition

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest
import io.github.amichne.kast.indexstore.snapshot.WorkspaceGenerationCommit
import java.util.concurrent.CancellationException

/**
 * Owns workspace freshness. Signals only invalidate and conflate work. Reconciliation and
 * identity verification are the only route to a published READY generation.
 */
internal class WorkspaceTransitionCoordinator(
    private val operations: WorkspaceTransitionOperations,
    initialPublished: PublishedWorkspaceGenerationManifest? = null,
    private val onTransition: (WorkspaceTransitionSnapshot) -> Unit = {},
    private val onBlocked: (TransitionBlocker, Throwable) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private val pendingSignals = linkedSetOf<WorkspaceSignal>()
    private var lifecycle = if (initialPublished == null) WorkspaceLifecycle.Dirty else WorkspaceLifecycle.Ready
    private var published = initialPublished
    private var blocker: TransitionBlocker? = null
    private var publicationWarning: WorkspaceGenerationCommit.DurabilityUncertain? = null
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
        val prepared = runPhase(TransitionPhase.Publishing) {
            operations.preparePublication(verified)
        }.getOrElse { return block(TransitionPhase.Publishing, it, cycle) }
        val identityAfterPreparation = runPhase(TransitionPhase.Verifying, operations::captureIdentity)
        val identityCaptureFailure = identityAfterPreparation.exceptionOrNull()
        if (identityCaptureFailure != null) {
            val discardFailure = runPhase(TransitionPhase.Publishing) {
                operations.discardPublication(prepared)
            }.exceptionOrNull()
            if (discardFailure != null) {
                discardFailure.addSuppressed(identityCaptureFailure)
                return block(TransitionPhase.Publishing, discardFailure, cycle)
            }
            return block(TransitionPhase.Verifying, identityCaptureFailure, cycle)
        }
        if (identityAfterPreparation.getOrThrow() != verified) {
            val discardFailure = runPhase(TransitionPhase.Publishing) {
                operations.discardPublication(prepared)
            }.exceptionOrNull()
            if (discardFailure != null) return block(TransitionPhase.Publishing, discardFailure, cycle)
            invalidate(cycle, includeAudit = true)
            return TransitionRun.Invalidated
        }
        val commitAllowed = synchronized(lock) {
            if (isCurrent(cycle)) {
                true
            } else {
                retainForRetry(cycle, includeAudit = false)
                false
            }
        }
        if (!commitAllowed) {
            val discardFailure = runPhase(TransitionPhase.Publishing) {
                operations.discardPublication(prepared)
            }.exceptionOrNull()
            if (discardFailure != null) return block(TransitionPhase.Publishing, discardFailure, cycle)
            emit(snapshot())
            return TransitionRun.Invalidated
        }

        val publicationAttempt = runPhase(TransitionPhase.Publishing) {
            operations.commitPublication(prepared)
        }
        val retryFailure = publicationAttempt.exceptionOrNull() as? WorkspaceTransitionRetryException
        if (retryFailure != null) {
            val discardFailure = runPhase(TransitionPhase.Publishing) {
                operations.discardPublication(prepared)
            }.exceptionOrNull()
            if (discardFailure != null) {
                discardFailure.addSuppressed(retryFailure)
                return block(TransitionPhase.Publishing, discardFailure, cycle)
            }
            return retry(TransitionPhase.Publishing, retryFailure, cycle)
        }
        var failure: Throwable? = null
        var failedBlocker: TransitionBlocker? = null
        var discard = publicationAttempt.isFailure
        val result = synchronized(lock) {
            val publication = publicationAttempt.getOrNull()
            if (publication == null) {
                val caught = checkNotNull(publicationAttempt.exceptionOrNull())
                retainForRetry(cycle, includeAudit = false)
                failure = caught
                failedBlocker = blockLocked(TransitionPhase.Publishing, caught)
                TransitionRun.Blocked
            } else {
                when (publication) {
                    is GenerationPublication.Published -> {
                        recordPublicationDurability(publication.commit)
                        if (isCurrent(cycle)) {
                            published = publication.manifest
                            blocker = null
                            lifecycle = WorkspaceLifecycle.Ready
                            TransitionRun.Published
                        } else {
                            retainForRetry(cycle, includeAudit = false)
                            discard = false
                            TransitionRun.Invalidated
                        }
                    }

                    GenerationPublication.InvalidatedBeforeCommit -> {
                        retainForRetry(cycle, includeAudit = false)
                        discard = true
                        TransitionRun.Invalidated
                    }

                    is GenerationPublication.InvalidatedAfterCommit -> {
                        recordPublicationDurability(publication.commit)
                        retainForRetry(cycle, includeAudit = false)
                        TransitionRun.Invalidated
                    }
                }
            }
        }
        if (discard) {
            runPhase(TransitionPhase.Publishing) {
                operations.discardPublication(prepared)
            }.onFailure { discardFailure ->
                if (failure == null) {
                    failure = discardFailure
                    failedBlocker = synchronized(lock) {
                        blockLocked(TransitionPhase.Publishing, discardFailure)
                    }
                }
            }
        }
        emit(snapshot())
        failure?.let { caught -> notifyBlocked(checkNotNull(failedBlocker), caught) }
        return if (failure == null) result else TransitionRun.Blocked
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
        if (failure is WorkspaceTransitionRetryException) return retry(phase, failure, cycle)
        val blockerAndState = synchronized(lock) {
            cycle?.let { pendingSignals += it.signals }
            val currentBlocker = blockLocked(phase, failure)
            currentBlocker to snapshotLocked()
        }
        emit(blockerAndState.second)
        notifyBlocked(blockerAndState.first, failure)
        return TransitionRun.Blocked
    }

    private fun retry(
        phase: TransitionPhase,
        failure: WorkspaceTransitionRetryException,
        cycle: TransitionCycle?,
    ): TransitionRun {
        val changed = synchronized(lock) {
            cycle?.let { pendingSignals += it.signals }
            blocker = TransitionBlocker(
                phase = phase,
                detail = failure.message?.takeIf(String::isNotBlank) ?: failure::class.qualifiedName.orEmpty(),
            )
            lifecycle = WorkspaceLifecycle.Dirty
            snapshotLocked()
        }
        emit(changed)
        return TransitionRun.Retry
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

    private fun recordPublicationDurability(commit: WorkspaceGenerationCommit) {
        publicationWarning = when (commit) {
            is WorkspaceGenerationCommit.Durable -> null
            is WorkspaceGenerationCommit.DurabilityUncertain -> commit
        }
    }

    private fun snapshotLocked(): WorkspaceTransitionSnapshot = WorkspaceTransitionSnapshot(
        lifecycle = lifecycle,
        pendingSignals = pendingSignals.toSet(),
        published = published,
        blocker = blocker,
        observedEventCount = observedEventCount,
        publicationWarning = publicationWarning,
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
