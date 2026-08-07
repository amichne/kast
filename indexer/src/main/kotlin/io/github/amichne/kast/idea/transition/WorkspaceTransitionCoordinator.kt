package io.github.amichne.kast.idea.transition

import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationState

/**
 * Owns workspace freshness. Signals only invalidate and conflate work. Reconciliation and
 * identity verification are the only route to a published READY generation.
 */
internal class WorkspaceTransitionCoordinator(
    private val operations: WorkspaceTransitionOperations,
    initialPublished: PublishedWorkspaceGenerationState = PublishedWorkspaceGenerationState.Unpublished,
    private val onTransition: (WorkspaceTransitionSnapshot) -> Unit = {},
    private val onBlocked: (TransitionBlocker, Throwable) -> Unit = { _, _ -> },
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
        val open = runTransitionEffect(operations::beginPublication)
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
                    operations.discardPublication(open)
                }
                discarded.exceptionOrNull()?.let { failure ->
                    return block(TransitionPhase.Publishing, failure, cycle)
                }
                invalidate(cycle, includeAudit = true)
                return TransitionRun.Invalidated
            }

            return publish(cycle, verified, open)
        } catch (failure: Throwable) {
            runCatching { operations.discardPublication(open) }
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
            operations.preparePublication(open, verified)
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
                operations.discardPublication(prepared)
            }.exceptionOrNull()
            if (discardFailure != null) {
                discardFailure.addSuppressed(identityCaptureFailure)
                return block(TransitionPhase.Publishing, discardFailure, cycle)
            }
            return block(TransitionPhase.Verifying, identityCaptureFailure, cycle)
        }
        if (identityAfterPreparation.getOrThrow() != verified) {
            val discardFailure = runTransitionEffect {
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
            val discardFailure = runTransitionEffect {
                operations.discardPublication(prepared)
            }.exceptionOrNull()
            if (discardFailure != null) return block(TransitionPhase.Publishing, discardFailure, cycle)
            emit(snapshot())
            return TransitionRun.Invalidated
        }

        val publicationAttempt = runTransitionEffect {
            operations.commitPublication(prepared)
        }
        val retryFailure = publicationAttempt.exceptionOrNull() as? WorkspaceTransitionRetryException
        if (retryFailure != null) {
            val discardFailure = runTransitionEffect {
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
                        if (isCurrent(cycle)) {
                            published = PublishedWorkspaceGenerationState.Published(publication.manifest)
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
            cycle?.let { retainForRetry(it, includeAudit = false) }
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
            cycle?.let { retainForRetry(it, includeAudit = false) }
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
        activeSourceFreshness = WorkspaceSourceFreshness.Absent
        return currentBlocker
    }

    private fun retainForRetry(cycle: TransitionCycle, includeAudit: Boolean) {
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
            operations.discardPublication(open)
        }.exceptionOrNull()
        if (discardFailure != null) failure.addSuppressed(discardFailure)
        return block(phase, failure, cycle)
    }

    private fun discardThenBlock(
        prepared: PreparedWorkspacePublication,
        phase: TransitionPhase,
        failure: Throwable,
        cycle: TransitionCycle,
    ): TransitionRun {
        val discardFailure = runTransitionEffect {
            operations.discardPublication(prepared)
        }.exceptionOrNull()
        if (discardFailure != null) failure.addSuppressed(discardFailure)
        return block(phase, failure, cycle)
    }

    private fun discardThenInvalidate(
        open: OpenWorkspacePublication,
        cycle: TransitionCycle,
    ): TransitionRun {
        val discardFailure = runTransitionEffect {
            operations.discardPublication(open)
        }.exceptionOrNull()
        if (discardFailure != null) return block(TransitionPhase.Publishing, discardFailure, cycle)
        emit(snapshot())
        return TransitionRun.Invalidated
    }

    private fun discardThenInvalidate(
        prepared: PreparedWorkspacePublication,
        cycle: TransitionCycle,
    ): TransitionRun {
        val discardFailure = runTransitionEffect {
            operations.discardPublication(prepared)
        }.exceptionOrNull()
        if (discardFailure != null) return block(TransitionPhase.Publishing, discardFailure, cycle)
        emit(snapshot())
        return TransitionRun.Invalidated
    }

    private fun emit(snapshot: WorkspaceTransitionSnapshot) {
        runCatching { onTransition(snapshot) }
    }

    private fun notifyBlocked(blocker: TransitionBlocker, failure: Throwable) {
        runCatching { onBlocked(blocker, failure) }
    }

}
