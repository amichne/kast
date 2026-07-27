package io.github.amichne.kast.api.continuation

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class ContinuationStoreOwnership<
    Token : Any,
    Query : Any,
    State : ContinuationOwnedState,
    Projection : ContinuationProjection,
>(
    capacity: ContinuationCapacity,
    timeToLive: ContinuationTtl,
    private val tokenIssuer: ContinuationTokenIssuer<Token>,
    private val stateDisposer: ContinuationStateDisposer<State>,
    clock: ContinuationClock,
) {
    private val lock = ReentrantLock()
    private val callbacksDrained = lock.newCondition()
    private val entries = ContinuationEntryRegistry<Token, Query, State>(
        capacity = capacity,
        timeToLive = timeToLive,
        clock = clock,
    )
    private val inFlightTokens = mutableMapOf<Token, Thread>()
    private val disposalThreads = mutableMapOf<Thread, Int>()
    private var activeCallbacks = 0
    private var activeDisposals = 0
    private var publicationInProgress = false
    private var publicationOwner: Thread? = null
    private var closing = false
    private var closeCompleted = false
    private var closeFailure: Throwable? = null
    fun prepareIssue(
        query: Query,
        state: State,
    ): ContinuationIssuePreparation<Token, Query, State> = lock.withLock {
        awaitPublicationLocked()
        if (closing) {
            ContinuationIssuePreparation.Rejected(
                disposal = registerStateDisposalLocked(state),
                failure = ContinuationAccessFailure.StoreClosed,
            )
        } else {
            val token = try {
                tokenIssuer.issue()
            } catch (failure: Throwable) {
                return@withLock ContinuationIssuePreparation.IssuerFailed(
                    disposal = registerStateDisposalLocked(state),
                    failure = failure,
                )
            }
            if (token in inFlightTokens) {
                return@withLock ContinuationIssuePreparation.Rejected(
                    disposal = registerStateDisposalLocked(state),
                    failure = ContinuationAccessFailure.TokenCollision,
                )
            }
            val nowNanos = entries.nowNanos()
            val discarded = entries.removeExpiredLocked(nowNanos).toMutableList()
            entries.removeLocked(token)?.let { replaced -> discarded += replaced.state }
            discarded += entries.removeForPublicationCapacityLocked(nowNanos)
            entries.scheduleNextExpiryLocked(nowNanos, this)
            startPublicationLocked()
            ContinuationIssuePreparation.Prepared(
                token = token,
                entry = ContinuationEntry(query, state, nowNanos),
                disposal = registerDisposalLocked(discarded),
            )
        }
    }

    fun completeIssuePublication(
        preparation: ContinuationIssuePreparation.Prepared<Token, Query, State>,
    ): RegisteredContinuationDisposal<State>? = lock.withLock {
        if (closing) {
            registerStateDisposalLocked(preparation.entry.state).also {
                finishPublicationLocked()
            }
        } else {
            entries.putLocked(preparation.token, preparation.entry)
            entries.scheduleNextExpiryLocked(entries.nowNanos(), this)
            finishPublicationLocked()
            null
        }
    }

    fun rollbackIssuePublication(state: State): RegisteredContinuationDisposal<State> =
        lock.withLock {
            registerStateDisposalLocked(state).also {
                entries.scheduleNextExpiryLocked(entries.nowNanos(), this)
                finishPublicationLocked()
            }
        }

    fun claim(token: Token, query: Query): ContinuationClaimDecision<Token, Query, State> =
        lock.withLock {
            if (closing) {
                return@withLock ContinuationClaimDecision.Rejected(
                    ContinuationAccessFailure.StoreClosed,
                )
            }
            val entry = entries.removeLocked(token)
                ?: return@withLock ContinuationClaimDecision.Rejected(
                    ContinuationAccessFailure.UnknownToken,
                )
            entries.scheduleNextExpiryLocked(entries.nowNanos(), this)
            val failure = when {
                entries.isExpired(entry) -> ContinuationAccessFailure.ExpiredToken
                entry.query != query -> ContinuationAccessFailure.QueryMismatch
                else -> null
            }
            if (failure != null) {
                ContinuationClaimDecision.Discarded(
                    disposal = registerStateDisposalLocked(entry.state),
                    failure = failure,
                )
            } else {
                check(inFlightTokens.putIfAbsent(token, Thread.currentThread()) == null) {
                    "Continuation token was already in flight"
                }
                activeCallbacks = Math.addExact(activeCallbacks, 1)
                ContinuationClaimDecision.Claimed(token, entry)
            }
        }

    fun invalidate(token: Token): ContinuationInvalidationDecision<State> = lock.withLock {
        if (closing) {
            return@withLock ContinuationInvalidationDecision.Rejected(
                ContinuationAccessFailure.StoreClosed,
            )
        }
        val entry = entries.removeLocked(token)
            ?: return@withLock ContinuationInvalidationDecision.Rejected(
                ContinuationAccessFailure.UnknownToken,
            )
        val expired = entries.isExpired(entry)
        entries.scheduleNextExpiryLocked(entries.nowNanos(), this)
        ContinuationInvalidationDecision.Discarded(
            disposal = registerStateDisposalLocked(entry.state),
            failure = if (expired) {
                ContinuationAccessFailure.ExpiredToken
            } else {
                null
            },
        )
    }

    fun retainLease(
        token: Token,
        entry: ContinuationEntry<Query, State>,
    ): ContinuationLeaseRetention<State> = lock.withLock {
        awaitPublicationLocked()
        if (closing || entries.isExpired(entry)) {
            ContinuationLeaseRetention.Terminal
        } else {
            check(!entries.containsLocked(token)) {
                "In-flight lease token was issued concurrently"
            }
            finishCallbackLocked(token, null)
            entries.putLocked(token, entry)
            val nowNanos = entries.nowNanos()
            val evicted = entries.removeOverCapacityLocked(nowNanos)
            entries.scheduleNextExpiryLocked(nowNanos, this)
            ContinuationLeaseRetention.Retained(registerDisposalLocked(evicted))
        }
    }

    fun prepareReissue(
        transition: ContinuationTransition.Reissue<Projection, Query>,
        state: State,
    ): ContinuationReissuePreparation<Token, Query, State> = lock.withLock {
        awaitPublicationLocked()
        if (closing) {
            ContinuationReissuePreparation.Terminal
        } else {
            val token = try {
                tokenIssuer.issue()
            } catch (failure: Throwable) {
                return@withLock ContinuationReissuePreparation.IssuerFailed(failure)
            }
            if (token in inFlightTokens) {
                return@withLock ContinuationReissuePreparation.Rejected(
                    ContinuationAccessFailure.TokenCollision,
                )
            }
            val nowNanos = entries.nowNanos()
            val discarded = entries.removeExpiredLocked(nowNanos).toMutableList()
            entries.removeLocked(token)?.let { replaced -> discarded += replaced.state }
            discarded += entries.removeForPublicationCapacityLocked(nowNanos)
            entries.scheduleNextExpiryLocked(nowNanos, this)
            startPublicationLocked()
            ContinuationReissuePreparation.Prepared(
                token = token,
                entry = ContinuationEntry(transition.nextQuery, state, nowNanos),
                disposal = registerDisposalLocked(discarded),
            )
        }
    }

    fun completeReissuePublication(
        claimedToken: Token,
        preparation: ContinuationReissuePreparation.Prepared<Token, Query, State>,
    ): RegisteredContinuationDisposal<State>? = lock.withLock {
        if (closing) {
            registerStateDisposalLocked(preparation.entry.state).also {
                finishPublicationLocked()
            }
        } else {
            finishCallbackLocked(claimedToken, null)
            entries.putLocked(preparation.token, preparation.entry)
            entries.scheduleNextExpiryLocked(entries.nowNanos(), this)
            finishPublicationLocked()
            null
        }
    }

    fun rollbackReissuePublication(state: State): RegisteredContinuationDisposal<State> =
        lock.withLock {
            registerStateDisposalLocked(state).also {
                entries.scheduleNextExpiryLocked(entries.nowNanos(), this)
                finishPublicationLocked()
            }
        }

    fun finishCallback(token: Token, disposeFailure: Throwable?) = lock.withLock {
        finishCallbackLocked(token, disposeFailure)
    }

    fun close() {
        val retained = lock.withLock {
            if (closing) {
                null
            } else {
                closing = true
                entries.cancelExpiryLocked()
                callbacksDrained.signalAll()
                registerDisposalLocked(entries.drainStatesLocked())
            }
        }
        disposeRegisteredCapturingFailure(retained)
        var reentrant = false
        val failure = lock.withLock {
            signalOwnershipDrainedLocked()
            if (isCurrentThreadStoreOwnedLocked()) {
                reentrant = true
                null
            } else {
                while (!closeCompleted) callbacksDrained.awaitUninterruptibly()
                closeFailure
            }
        }
        if (reentrant) return
        failure?.let { throw it }
    }

    fun expirePassively() {
        val disposal = lock.withLock {
            entries.expiryTaskCompletedLocked()
            if (closing) {
                null
            } else {
                val nowNanos = entries.nowNanos()
                val expired = entries.removeExpiredLocked(nowNanos)
                entries.scheduleNextExpiryLocked(nowNanos, this)
                registerDisposalLocked(expired)
            }
        }
        disposeRegisteredCapturingFailure(disposal)
    }

    fun disposeRegistered(disposal: RegisteredContinuationDisposal<State>?) {
        disposeRegisteredCapturingFailure(disposal)?.let { throw it }
    }

    fun disposeRegisteredCapturingFailure(
        disposal: RegisteredContinuationDisposal<State>?,
    ): Throwable? {
        if (disposal == null) return null
        val thread = Thread.currentThread()
        lock.withLock {
            disposalThreads[thread] = Math.addExact(disposalThreads[thread] ?: 0, 1)
        }
        val failure = try {
            disposeAllCapturingFailure(disposal.states)
        } catch (unexpectedFailure: Throwable) {
            unexpectedFailure
        }
        finishDisposal(thread, failure)
        return failure
    }

    fun disposeCapturingFailure(state: State): Throwable? = try {
        stateDisposer.dispose(state)
        null
    } catch (failure: Throwable) {
        failure
    }

    fun isClosingLocked(): Boolean = closing

    private fun finishCallbackLocked(token: Token, disposeFailure: Throwable?) {
        check(inFlightTokens.remove(token) != null) {
            "Continuation callback token was not in flight"
        }
        activeCallbacks = Math.subtractExact(activeCallbacks, 1)
        if (closing) recordCloseFailureLocked(disposeFailure)
        signalOwnershipDrainedLocked()
    }

    private fun awaitPublicationLocked() {
        while (publicationInProgress && !closing) callbacksDrained.awaitUninterruptibly()
    }

    private fun startPublicationLocked() {
        check(!publicationInProgress) { "Continuation publication was already in progress" }
        publicationInProgress = true
        publicationOwner = Thread.currentThread()
    }

    private fun finishPublicationLocked() {
        check(publicationInProgress) { "Continuation publication was not in progress" }
        publicationInProgress = false
        publicationOwner = null
        signalOwnershipDrainedLocked()
    }

    private fun registerDisposalLocked(
        states: List<State>,
    ): RegisteredContinuationDisposal<State>? {
        if (states.isEmpty()) return null
        activeDisposals = Math.addExact(activeDisposals, 1)
        return RegisteredContinuationDisposal(states)
    }

    private fun registerStateDisposalLocked(
        state: State,
    ): RegisteredContinuationDisposal<State> {
        activeDisposals = Math.addExact(activeDisposals, 1)
        return RegisteredContinuationDisposal(listOf(state))
    }

    private fun finishDisposal(thread: Thread, failure: Throwable?) {
        lock.withLock {
            val ownershipCount = checkNotNull(disposalThreads[thread]) {
                "Continuation disposal thread was not registered"
            }
            if (ownershipCount == 1) {
                disposalThreads.remove(thread)
            } else {
                disposalThreads[thread] = Math.subtractExact(ownershipCount, 1)
            }
            activeDisposals = Math.subtractExact(activeDisposals, 1)
            if (closing) recordCloseFailureLocked(failure)
            signalOwnershipDrainedLocked()
        }
    }

    private fun signalOwnershipDrainedLocked() {
        if (
            closing &&
            !closeCompleted &&
            activeCallbacks == 0 &&
            activeDisposals == 0 &&
            !publicationInProgress
        ) {
            closeCompleted = true
        }
        callbacksDrained.signalAll()
    }

    private fun isCurrentThreadStoreOwnedLocked(): Boolean {
        val thread = Thread.currentThread()
        return publicationOwner === thread ||
            thread in disposalThreads ||
            inFlightTokens.values.any { owner -> owner === thread }
    }

    private fun disposeAllCapturingFailure(states: List<State>): Throwable? {
        var firstFailure: Throwable? = null
        states.forEach { state ->
            val failure = disposeCapturingFailure(state) ?: return@forEach
            if (firstFailure == null) {
                firstFailure = failure
            } else {
                firstFailure.addSuppressed(failure)
            }
        }
        return firstFailure
    }

    private fun recordCloseFailureLocked(failure: Throwable?) {
        if (failure == null) return
        if (closeFailure == null) {
            closeFailure = failure
        } else if (closeFailure !== failure) {
            closeFailure?.addSuppressed(failure)
        }
    }
}
