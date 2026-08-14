package io.github.amichne.kast.workspace.service

import io.github.amichne.kast.evidence.contract.GenerationPublication
import io.github.amichne.kast.evidence.contract.OpenWorkspacePublication
import io.github.amichne.kast.evidence.contract.PreparedWorkspacePublication
import io.github.amichne.kast.evidence.contract.WorkspaceGraphPublication
import io.github.amichne.kast.evidence.spi.WorkspacePublicationAuthority
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGenerationState
import io.github.amichne.kast.workspace.contract.TransitionBlocker
import io.github.amichne.kast.workspace.contract.TransitionBlockerKind
import io.github.amichne.kast.workspace.contract.TransitionPhase
import io.github.amichne.kast.workspace.contract.TransitionRun
import io.github.amichne.kast.workspace.contract.WorkspaceLifecycle
import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import io.github.amichne.kast.workspace.contract.WorkspaceSourceFreshness
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceTransitionRequest
import io.github.amichne.kast.workspace.contract.WorkspaceTransitionSnapshot
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionFailureClassifier
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionFailureDisposition
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionOperations

/** Owns the single workspace freshness and READY-publication transition. */
class WorkspaceTransitionCoordinator(
    private val operations: WorkspaceTransitionOperations,
    private val publication: WorkspacePublicationAuthority,
    private val graphPublication: () -> WorkspaceGraphPublication,
    private val failureClassifier: WorkspaceTransitionFailureClassifier,
    initialPublished: PublishedWorkspaceGenerationState = PublishedWorkspaceGenerationState.Unpublished,
    private val onTransition: (WorkspaceTransitionSnapshot) -> Unit = {},
    private val onBlocked: (TransitionBlocker) -> Unit = {},
) {
    private val lock = Any()
    private val pendingSignals = linkedSetOf<WorkspaceSignal>()
    private var pendingSourceFreshness: WorkspaceSourceFreshness = WorkspaceSourceFreshness.Absent
    private var activeSourceFreshness: WorkspaceSourceFreshness = WorkspaceSourceFreshness.Absent
    private var lifecycle = when (initialPublished) {
        PublishedWorkspaceGenerationState.Unpublished -> WorkspaceLifecycle.Dirty
        is PublishedWorkspaceGenerationState.Published -> WorkspaceLifecycle.Ready
    }
    private var published = initialPublished
    private var blocker: TransitionBlocker? = null
    private var observedEventCount = 0L

    fun observe(signal: WorkspaceSignal) = observe(WorkspaceTransitionRequest.Unkeyed(signal))

    fun observe(request: WorkspaceTransitionRequest) {
        val changed = synchronized(lock) {
            observedEventCount = Math.addExact(observedEventCount, 1)
            pendingSignals += request.signal
            pendingSourceFreshness = pendingSourceFreshness.followedBy(
                WorkspaceSourceFreshness.from(request),
            )
            blocker = null
            if (lifecycle != WorkspaceLifecycle.Settling) {
                lifecycle = WorkspaceLifecycle.Dirty
                activeSourceFreshness = WorkspaceSourceFreshness.Absent
            }
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
        runTransitionEffect { operations.settle(settling.first) }
            .onFailure { return block(TransitionPhase.Settling, it) }

        val cycleAndState = synchronized(lock) {
            val cycle = TransitionCycle(
                signals = pendingSignals.toSet(),
                observedEventCount = observedEventCount,
                sourceFreshness = pendingSourceFreshness,
            )
            pendingSignals.clear()
            pendingSourceFreshness = WorkspaceSourceFreshness.Absent
            activeSourceFreshness = cycle.sourceFreshness
            lifecycle = WorkspaceLifecycle.Refreshing
            cycle to snapshotLocked()
        }
        val cycle = cycleAndState.first
        emit(cycleAndState.second)

        runTransitionEffect { operations.refresh(cycle.signals) }
            .onFailure { return block(TransitionPhase.Refreshing, it, cycle) }
        if (!advance(cycle, WorkspaceLifecycle.Reconciling)) return TransitionRun.Invalidated

        val candidate = runTransitionEffect(operations::captureIdentity)
            .getOrElse { return block(TransitionPhase.Reconciling, it, cycle) }
        val open = runTransitionEffect(publication::begin)
            .getOrElse { return block(TransitionPhase.Publishing, it, cycle) }
        try {
            val reconciliation = runTransitionEffect { operations.reconcile(candidate) }
            val reconciliationFailure = reconciliation.exceptionOrNull()
            if (reconciliationFailure != null) {
                return discardThenBlock(open, TransitionPhase.Reconciling, reconciliationFailure, cycle)
            }
            val reconciledCandidate = reconciliation.getOrThrow()
            if (!advance(cycle, WorkspaceLifecycle.Verifying)) {
                return discardThenInvalidate(open, cycle)
            }

            val verification = runTransitionEffect(operations::captureIdentity)
            val verificationFailure = verification.exceptionOrNull()
            if (verificationFailure != null) {
                return discardThenBlock(open, TransitionPhase.Verifying, verificationFailure, cycle)
            }
            val verified = verification.getOrThrow()
            if (reconciledCandidate != verified) {
                val discarded = runTransitionEffect {
                    publication.discard(open)
                }
                discarded.exceptionOrNull()?.let { failure ->
                    return block(TransitionPhase.Publishing, failure, cycle)
                }
                invalidate(cycle, includeAudit = true)
                return TransitionRun.Invalidated
            }

            return publish(cycle, verified, open)
        } catch (failure: Throwable) {
            runCatching { publication.discard(open) }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun publish(
        cycle: TransitionCycle,
        verified: WorkspaceStateIdentity,
        open: OpenWorkspacePublication,
    ): TransitionRun {
        val preparation = runTransitionEffect {
            publication.prepare(open, verified, graphPublication())
        }
        val preparationFailure = preparation.exceptionOrNull()
        if (preparationFailure != null) {
            return discardThenBlock(open, TransitionPhase.Publishing, preparationFailure, cycle)
        }
        val prepared = preparation.getOrThrow()
        val identityAfterPreparation = runTransitionEffect(operations::captureIdentity)
        val identityCaptureFailure = identityAfterPreparation.exceptionOrNull()
        if (identityCaptureFailure != null) {
            val discardFailure = runTransitionEffect {
                publication.discard(prepared)
            }.exceptionOrNull()
            if (discardFailure != null) {
                discardFailure.addSuppressed(identityCaptureFailure)
                return block(TransitionPhase.Publishing, discardFailure, cycle)
            }
            return block(TransitionPhase.Verifying, identityCaptureFailure, cycle)
        }
        if (identityAfterPreparation.getOrThrow() != verified) {
            val discardFailure = runTransitionEffect {
                publication.discard(prepared)
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
            val discardFailure = runTransitionEffect {
                publication.discard(prepared)
            }.exceptionOrNull()
            if (discardFailure != null) return block(TransitionPhase.Publishing, discardFailure, cycle)
            emit(snapshot())
            return TransitionRun.Invalidated
        }

        val publicationAttempt = runTransitionEffect {
            publication.commit(prepared)
        }
        val retryFailure = publicationAttempt.exceptionOrNull()
        val retryDisposition = retryFailure
            ?.let(failureClassifier::classify)
            as? WorkspaceTransitionFailureDisposition.Retry
        if (retryFailure != null && retryDisposition != null) {
            val discardFailure = runTransitionEffect {
                publication.discard(prepared)
            }.exceptionOrNull()
            if (discardFailure != null) {
                discardFailure.addSuppressed(retryFailure)
                return block(TransitionPhase.Publishing, discardFailure, cycle)
            }
            return retry(TransitionPhase.Publishing, retryDisposition.detail, cycle)
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
                        if (isCurrent(cycle)) {
                            published = PublishedWorkspaceGenerationState.Published(
                                publication.commit.publication,
                            )
                            blocker = null
                            lifecycle = WorkspaceLifecycle.Ready
                            activeSourceFreshness = WorkspaceSourceFreshness.Absent
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
                        retainForRetry(cycle, includeAudit = false)
                        TransitionRun.Invalidated
                    }
                }
            }
        }
        if (discard) {
            runTransitionEffect {
                publication.discard(prepared)
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
        failure?.let { notifyBlocked(checkNotNull(failedBlocker)) }
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

    private fun invalidate(
        cycle: TransitionCycle,
        includeAudit: Boolean,
    ) {
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
        when (val disposition = failureClassifier.classify(failure)) {
            WorkspaceTransitionFailureDisposition.Cancellation -> throw failure
            is WorkspaceTransitionFailureDisposition.Retry ->
                return retry(phase, disposition.detail, cycle)
            is WorkspaceTransitionFailureDisposition.Blocked -> Unit
        }
        val blockerAndState = synchronized(lock) {
            cycle?.let { retainForRetry(it, includeAudit = false) }
            val currentBlocker = blockLocked(phase, failure)
            currentBlocker to snapshotLocked()
        }
        emit(blockerAndState.second)
        notifyBlocked(blockerAndState.first)
        return TransitionRun.Blocked
    }

    private fun retry(
        phase: TransitionPhase,
        detail: String,
        cycle: TransitionCycle?,
    ): TransitionRun {
        val changed = synchronized(lock) {
            cycle?.let { retainForRetry(it, includeAudit = false) }
            blocker = TransitionBlocker(
                phase = phase,
                kind = TransitionBlockerKind.RetryableTransition,
                detail = detail,
            )
            lifecycle = WorkspaceLifecycle.Dirty
            snapshotLocked()
        }
        emit(changed)
        return TransitionRun.Retry
    }

    private fun blockLocked(
        phase: TransitionPhase,
        failure: Throwable,
    ): TransitionBlocker {
        val currentBlocker = failureClassifier.classify(failure).toBlocker(phase, failure)
        blocker = currentBlocker
        lifecycle = WorkspaceLifecycle.Blocked
        activeSourceFreshness = WorkspaceSourceFreshness.Absent
        return currentBlocker
    }

    private fun retainForRetry(
        cycle: TransitionCycle,
        includeAudit: Boolean,
    ) {
        pendingSignals += cycle.signals
        pendingSourceFreshness = cycle.sourceFreshness.followedBy(pendingSourceFreshness)
        if (includeAudit) pendingSignals += WorkspaceSignal.RecoveryAudit
        lifecycle = WorkspaceLifecycle.Dirty
        activeSourceFreshness = WorkspaceSourceFreshness.Absent
    }

    private fun isCurrent(cycle: TransitionCycle): Boolean =
        observedEventCount == cycle.observedEventCount && pendingSignals.isEmpty()

    private fun snapshotLocked(): WorkspaceTransitionSnapshot = WorkspaceTransitionSnapshot(
        lifecycle = lifecycle,
        pendingSignals = pendingSignals.toSet(),
        published = published,
        blocker = blocker,
        observedEventCount = observedEventCount,
        activeSourceFreshness = activeSourceFreshness,
    )

    private fun discardThenBlock(
        open: OpenWorkspacePublication,
        phase: TransitionPhase,
        failure: Throwable,
        cycle: TransitionCycle,
    ): TransitionRun {
        val discardFailure = runTransitionEffect {
            publication.discard(open)
        }.exceptionOrNull()
        if (discardFailure != null) failure.addSuppressed(discardFailure)
        return block(phase, failure, cycle)
    }

    private fun discardThenInvalidate(
        open: OpenWorkspacePublication,
        cycle: TransitionCycle,
    ): TransitionRun {
        val discardFailure = runTransitionEffect {
            publication.discard(open)
        }.exceptionOrNull()
        if (discardFailure != null) return block(TransitionPhase.Publishing, discardFailure, cycle)
        emit(snapshot())
        return TransitionRun.Invalidated
    }

    private fun emit(snapshot: WorkspaceTransitionSnapshot) = runCatching { onTransition(snapshot) }

    private fun notifyBlocked(blocker: TransitionBlocker) = runCatching { onBlocked(blocker) }
}
