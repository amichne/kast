package io.github.amichne.kast.api.continuation

import java.lang.ref.WeakReference
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ServerHeldContinuationStore<Token : Any, Query : Any, State : Any, Projection>(
    private val capacity: ContinuationCapacity,
    private val timeToLive: ContinuationTtl,
    private val tokenIssuer: ContinuationTokenIssuer<Token>,
    private val stateDisposer: ContinuationStateDisposer<State>,
    private val clock: ContinuationClock = ContinuationClock.System,
) : AutoCloseable {
    private val lock = ReentrantLock()
    private val callbacksDrained = lock.newCondition()
    private val entries = LinkedHashMap<Token, Entry<Query, State>>()
    private val inFlightTokens = mutableSetOf<Token>()
    private var expiryTask: ScheduledFuture<*>? = null
    private var activeCallbacks = 0
    private var activeDisposals = 0
    private var closing = false
    private var closeCompleted = false
    private var closeFailure: Throwable? = null

    fun issue(query: Query, state: State): ContinuationIssueResult<Token> {
        val decision = lock.withLock {
            if (closing) {
                IssueDecision.Rejected(
                    disposal = registerStateDisposalLocked(state),
                    failure = ContinuationAccessFailure.StoreClosed,
                )
            } else {
                val token = try {
                    tokenIssuer.issue()
                } catch (failure: Throwable) {
                    return@withLock IssueDecision.IssuerFailed(
                        disposal = registerStateDisposalLocked(state),
                        failure = failure,
                    )
                }
                if (token in inFlightTokens) {
                    return@withLock IssueDecision.Rejected(
                        disposal = registerStateDisposalLocked(state),
                        failure = ContinuationAccessFailure.TokenCollision,
                    )
                }
                val nowNanos = clock.nowNanos()
                val discarded = removeExpiredLocked(nowNanos).toMutableList()
                entries.remove(token)?.let { replaced -> discarded += replaced.state }
                entries[token] = Entry(query, state, nowNanos)
                discarded += removeOverCapacityLocked(nowNanos)
                scheduleNextExpiryLocked(nowNanos)
                IssueDecision.Issued(token, registerDisposalLocked(discarded))
            }
        }
        return when (decision) {
            is IssueDecision.Issued -> {
                disposeRegistered(decision.disposal)
                ContinuationIssueResult.Issued(decision.token)
            }
            is IssueDecision.Rejected -> {
                disposeRegistered(decision.disposal)
                ContinuationIssueResult.Rejected(decision.failure)
            }
            is IssueDecision.IssuerFailed -> {
                val disposeFailure = disposeRegisteredCapturingFailure(decision.disposal)
                disposeFailure?.let(decision.failure::addSuppressed)
                throw decision.failure
            }
        }
    }

    fun lease(
        token: Token,
        query: Query,
        projection: ContinuationStateProjection<State, Projection>,
    ): ContinuationLeaseResult<Projection> = when (val claim = claim(token, query)) {
        is ClaimDecision.Rejected -> ContinuationLeaseResult.Rejected(claim.failure)
        is ClaimDecision.Discarded -> {
            disposeRegistered(claim.disposal)
            ContinuationLeaseResult.Rejected(claim.failure)
        }
        is ClaimDecision.Claimed -> leaseClaim(claim.token, claim.entry, projection)
    }

    fun consume(
        token: Token,
        query: Query,
        projection: ContinuationStateProjection<State, ContinuationTransition<Projection, Query>>,
    ): ContinuationConsumeResult<Token, Projection> = when (val claim = claim(token, query)) {
        is ClaimDecision.Rejected -> ContinuationConsumeResult.Rejected(claim.failure)
        is ClaimDecision.Discarded -> {
            disposeRegistered(claim.disposal)
            ContinuationConsumeResult.Rejected(claim.failure)
        }
        is ClaimDecision.Claimed -> consumeClaim(claim.token, claim.entry.state, projection)
    }

    fun invalidate(token: Token): ContinuationInvalidationResult {
        val decision = lock.withLock {
            when {
                closing -> InvalidationDecision.Rejected(ContinuationAccessFailure.StoreClosed)
                else -> {
                    val entry = entries.remove(token)
                        ?: return@withLock InvalidationDecision.Rejected(
                            ContinuationAccessFailure.UnknownToken,
                        )
                    if (elapsedNanos(entry.createdAtNanos) >= timeToLive.nanoseconds) {
                        scheduleNextExpiryLocked(clock.nowNanos())
                        InvalidationDecision.Discarded(
                            registerStateDisposalLocked(entry.state),
                            ContinuationAccessFailure.ExpiredToken,
                        )
                    } else {
                        scheduleNextExpiryLocked(clock.nowNanos())
                        InvalidationDecision.Discarded(
                            registerStateDisposalLocked(entry.state),
                            null,
                        )
                    }
                }
            }
        }
        return when (decision) {
            is InvalidationDecision.Rejected -> ContinuationInvalidationResult.Rejected(decision.failure)
            is InvalidationDecision.Discarded -> {
                disposeRegistered(decision.disposal)
                decision.failure?.let(ContinuationInvalidationResult::Rejected)
                    ?: ContinuationInvalidationResult.Invalidated
            }
        }
    }

    override fun close() {
        val retained = lock.withLock {
            if (closing) {
                while (!closeCompleted) callbacksDrained.awaitUninterruptibly()
                null
            } else {
                closing = true
                expiryTask?.cancel(false)
                expiryTask = null
                entries.values.map(Entry<Query, State>::state).also { entries.clear() }
            }
        }
        if (retained == null) {
            lock.withLock { closeFailure }?.let { throw it }
            return
        }

        val retainedFailure = disposeAllCapturingFailure(retained)
        val failure = lock.withLock {
            recordCloseFailureLocked(retainedFailure)
            while (activeCallbacks > 0 || activeDisposals > 0) {
                callbacksDrained.awaitUninterruptibly()
            }
            closeCompleted = true
            callbacksDrained.signalAll()
            closeFailure
        }
        failure?.let { throw it }
    }

    private fun claim(token: Token, query: Query): ClaimDecision<Token, Query, State> = lock.withLock {
        if (closing) return@withLock ClaimDecision.Rejected(ContinuationAccessFailure.StoreClosed)
        val entry = entries.remove(token)
            ?: return@withLock ClaimDecision.Rejected(ContinuationAccessFailure.UnknownToken)
        scheduleNextExpiryLocked(clock.nowNanos())
        val failure = when {
            elapsedNanos(entry.createdAtNanos) >= timeToLive.nanoseconds ->
                ContinuationAccessFailure.ExpiredToken
            entry.query != query -> ContinuationAccessFailure.QueryMismatch
            else -> null
        }
        if (failure != null) {
            ClaimDecision.Discarded(
                disposal = registerStateDisposalLocked(entry.state),
                failure = failure,
            )
        } else {
            check(inFlightTokens.add(token)) { "Continuation token was already in flight" }
            activeCallbacks = Math.addExact(activeCallbacks, 1)
            ClaimDecision.Claimed(token, entry)
        }
    }

    private fun leaseClaim(
        token: Token,
        entry: Entry<Query, State>,
        projection: ContinuationStateProjection<State, Projection>,
    ): ContinuationLeaseResult<Projection> {
        val output = try {
            projection.project(entry.state)
        } catch (failure: Throwable) {
            val disposeFailure = disposeCapturingFailure(entry.state)
            finishCallback(token, disposeFailure)
            disposeFailure?.let(failure::addSuppressed)
            throw failure
        }

        val retainDecision = lock.withLock {
            if (closing || elapsedNanos(entry.createdAtNanos) >= timeToLive.nanoseconds) {
                LeaseRetention.Terminal
            } else {
                check(token !in entries) { "In-flight lease token was issued concurrently" }
                finishCallbackLocked(token, null)
                entries[token] = entry
                val nowNanos = clock.nowNanos()
                val evicted = removeOverCapacityLocked(nowNanos)
                scheduleNextExpiryLocked(nowNanos)
                LeaseRetention.Retained(registerDisposalLocked(evicted))
            }
        }
        val disposeFailure = when (retainDecision) {
            LeaseRetention.Terminal -> disposeCapturingFailure(entry.state)
            is LeaseRetention.Retained -> disposeRegisteredCapturingFailure(retainDecision.disposal)
        }
        if (retainDecision is LeaseRetention.Terminal) finishCallback(token, disposeFailure)
        disposeFailure?.let { throw it }
        return ContinuationLeaseResult.Granted(output)
    }

    private fun consumeClaim(
        token: Token,
        state: State,
        projection: ContinuationStateProjection<State, ContinuationTransition<Projection, Query>>,
    ): ContinuationConsumeResult<Token, Projection> {
        val transition = try {
            projection.project(state)
        } catch (failure: Throwable) {
            val disposeFailure = disposeCapturingFailure(state)
            finishCallback(token, disposeFailure)
            disposeFailure?.let(failure::addSuppressed)
            throw failure
        }

        return when (transition) {
            is ContinuationTransition.Complete -> {
                val disposeFailure = disposeCapturingFailure(state)
                finishCallback(token, disposeFailure)
                disposeFailure?.let { throw it }
                ContinuationConsumeResult.Completed(transition.output)
            }
            is ContinuationTransition.Reissue -> reissueClaim(token, state, transition)
        }
    }

    private fun reissueClaim(
        claimedToken: Token,
        state: State,
        transition: ContinuationTransition.Reissue<Projection, Query>,
    ): ContinuationConsumeResult<Token, Projection> {
        val decision = lock.withLock {
            if (closing) {
                ReissueDecision.Terminal
            } else {
                val token = try {
                    tokenIssuer.issue()
                } catch (failure: Throwable) {
                    return@withLock ReissueDecision.IssuerFailed(failure)
                }
                if (token in inFlightTokens) {
                    return@withLock ReissueDecision.Rejected(
                        ContinuationAccessFailure.TokenCollision,
                    )
                }
                val nowNanos = clock.nowNanos()
                val discarded = removeExpiredLocked(nowNanos).toMutableList()
                entries.remove(token)?.let { replaced -> discarded += replaced.state }
                finishCallbackLocked(claimedToken, null)
                entries[token] = Entry(transition.nextQuery, state, nowNanos)
                discarded += removeOverCapacityLocked(nowNanos)
                scheduleNextExpiryLocked(nowNanos)
                ReissueDecision.Reissued(token, registerDisposalLocked(discarded))
            }
        }
        return when (decision) {
            ReissueDecision.Terminal -> {
                val disposeFailure = disposeCapturingFailure(state)
                finishCallback(claimedToken, disposeFailure)
                disposeFailure?.let { throw it }
                ContinuationConsumeResult.Rejected(ContinuationAccessFailure.StoreClosed)
            }
            is ReissueDecision.Rejected -> {
                val disposeFailure = disposeCapturingFailure(state)
                finishCallback(claimedToken, disposeFailure)
                disposeFailure?.let { throw it }
                ContinuationConsumeResult.Rejected(decision.failure)
            }
            is ReissueDecision.IssuerFailed -> {
                val disposeFailure = disposeCapturingFailure(state)
                finishCallback(claimedToken, disposeFailure)
                disposeFailure?.let(decision.failure::addSuppressed)
                throw decision.failure
            }
            is ReissueDecision.Reissued -> {
                val disposeFailure = disposeRegisteredCapturingFailure(decision.disposal)
                disposeFailure?.let { throw it }
                ContinuationConsumeResult.Reissued(transition.output, decision.token)
            }
        }
    }

    private fun finishCallback(token: Token, disposeFailure: Throwable?) {
        lock.withLock {
            finishCallbackLocked(token, disposeFailure)
        }
    }

    private fun finishCallbackLocked(token: Token, disposeFailure: Throwable?) {
        check(inFlightTokens.remove(token)) { "Continuation callback token was not in flight" }
        activeCallbacks = Math.subtractExact(activeCallbacks, 1)
        if (closing) recordCloseFailureLocked(disposeFailure)
        signalOwnershipDrainedLocked()
    }

    private fun removeExpiredLocked(nowNanos: Long): List<State> = buildList {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (nowNanos - entry.createdAtNanos >= timeToLive.nanoseconds) {
                iterator.remove()
                add(entry.state)
            }
        }
    }

    private fun removeOverCapacityLocked(nowNanos: Long): List<State> = buildList {
        while (entries.size > capacity.value) {
            val oldest = entries.entries.maxBy { (_, entry) ->
                nowNanos - entry.createdAtNanos
            }
            entries.remove(oldest.key)
            add(oldest.value.state)
        }
    }

    private fun scheduleNextExpiryLocked(nowNanos: Long) {
        expiryTask?.cancel(false)
        expiryTask = null
        if (closing || entries.isEmpty()) return
        val elapsedNanos = entries.values.maxOf { entry -> nowNanos - entry.createdAtNanos }
        val delayNanos = (timeToLive.nanoseconds - elapsedNanos).coerceAtLeast(0L)
        expiryTask = EXPIRY_EXECUTOR.schedule(
            PassiveExpiryTask(this),
            delayNanos,
            TimeUnit.NANOSECONDS,
        )
    }

    private fun expirePassively() {
        val disposal = lock.withLock {
            expiryTask = null
            if (closing) {
                null
            } else {
                val nowNanos = clock.nowNanos()
                val expired = removeExpiredLocked(nowNanos)
                scheduleNextExpiryLocked(nowNanos)
                registerDisposalLocked(expired)
            }
        }
        disposeRegisteredCapturingFailure(disposal)
    }

    private fun elapsedNanos(createdAtNanos: Long): Long = clock.nowNanos() - createdAtNanos

    private fun registerDisposalLocked(states: List<State>): RegisteredDisposal<State>? {
        if (states.isEmpty()) return null
        activeDisposals = Math.addExact(activeDisposals, 1)
        return RegisteredDisposal(states)
    }

    private fun registerStateDisposalLocked(state: State): RegisteredDisposal<State> {
        activeDisposals = Math.addExact(activeDisposals, 1)
        return RegisteredDisposal(listOf(state))
    }

    private fun disposeRegistered(disposal: RegisteredDisposal<State>?) {
        disposeRegisteredCapturingFailure(disposal)?.let { throw it }
    }

    private fun disposeRegisteredCapturingFailure(disposal: RegisteredDisposal<State>?): Throwable? {
        if (disposal == null) return null
        val failure = disposeAllCapturingFailure(disposal.states)
        finishDisposal(failure)
        return failure
    }

    private fun finishDisposal(failure: Throwable?) {
        lock.withLock {
            activeDisposals = Math.subtractExact(activeDisposals, 1)
            if (closing) recordCloseFailureLocked(failure)
            signalOwnershipDrainedLocked()
        }
    }

    private fun signalOwnershipDrainedLocked() {
        if (activeCallbacks == 0 && activeDisposals == 0) callbacksDrained.signalAll()
    }

    private fun disposeCapturingFailure(state: State): Throwable? = try {
        stateDisposer.dispose(state)
        null
    } catch (failure: Throwable) {
        failure
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

    private data class Entry<Query, State>(
        val query: Query,
        val state: State,
        val createdAtNanos: Long,
    )

    private data class RegisteredDisposal<out State>(val states: List<State>)

    private sealed interface IssueDecision<out Token, out State> {
        data class Issued<Token, State>(
            val token: Token,
            val disposal: RegisteredDisposal<State>?,
        ) : IssueDecision<Token, State>

        data class Rejected<State>(
            val disposal: RegisteredDisposal<State>,
            val failure: ContinuationAccessFailure,
        ) : IssueDecision<Nothing, State>

        data class IssuerFailed<State>(
            val disposal: RegisteredDisposal<State>,
            val failure: Throwable,
        ) : IssueDecision<Nothing, State>
    }

    private sealed interface ClaimDecision<out Token, out Query, out State> {
        data class Claimed<Token, Query, State>(
            val token: Token,
            val entry: Entry<Query, State>,
        ) : ClaimDecision<Token, Query, State>

        data class Discarded<State>(
            val disposal: RegisteredDisposal<State>,
            val failure: ContinuationAccessFailure,
        ) : ClaimDecision<Nothing, Nothing, State>

        data class Rejected(
            val failure: ContinuationAccessFailure,
        ) : ClaimDecision<Nothing, Nothing, Nothing>
    }

    private sealed interface LeaseRetention<out State> {
        data object Terminal : LeaseRetention<Nothing>

        data class Retained<State>(
            val disposal: RegisteredDisposal<State>?,
        ) : LeaseRetention<State>
    }

    private sealed interface ReissueDecision<out Token, out State> {
        data object Terminal : ReissueDecision<Nothing, Nothing>

        data class Reissued<Token, State>(
            val token: Token,
            val disposal: RegisteredDisposal<State>?,
        ) : ReissueDecision<Token, State>

        data class Rejected(
            val failure: ContinuationAccessFailure,
        ) : ReissueDecision<Nothing, Nothing>

        data class IssuerFailed(val failure: Throwable) : ReissueDecision<Nothing, Nothing>
    }

    private sealed interface InvalidationDecision<out State> {
        data class Discarded<State>(
            val disposal: RegisteredDisposal<State>,
            val failure: ContinuationAccessFailure?,
        ) : InvalidationDecision<State>

        data class Rejected(
            val failure: ContinuationAccessFailure,
        ) : InvalidationDecision<Nothing>
    }

    private class PassiveExpiryTask<Token : Any, Query : Any, State : Any, Projection>(
        store: ServerHeldContinuationStore<Token, Query, State, Projection>,
    ) : Runnable {
        private val store = WeakReference(store)

        override fun run() {
            runCatching { store.get()?.expirePassively() }
        }
    }

    private companion object {
        val EXPIRY_EXECUTOR = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "kast-continuation-expiry").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
        }
    }
}
