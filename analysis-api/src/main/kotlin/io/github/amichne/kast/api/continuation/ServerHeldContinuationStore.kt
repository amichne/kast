package io.github.amichne.kast.api.continuation

import java.lang.ref.WeakReference
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ServerHeldContinuationStore<Token : Any, Query : Any, State : Any>(
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
    private var closing = false
    private var closeCompleted = false
    private var closeFailure: Throwable? = null

    fun issue(query: Query, state: State): ContinuationIssueResult<Token> {
        val decision = lock.withLock {
            if (closing) {
                IssueDecision.Rejected(state)
            } else {
                val nowNanos = clock.nowNanos()
                val token = tokenIssuer.issue()
                check(token !in inFlightTokens) {
                    "Continuation token issuer returned a token owned by an in-flight callback"
                }
                val discarded = removeExpiredLocked(nowNanos).toMutableList()
                entries.remove(token)?.let { replaced -> discarded += replaced.state }
                entries[token] = Entry(query, state, nowNanos)
                discarded += removeOverCapacityLocked()
                scheduleNextExpiryLocked(nowNanos)
                IssueDecision.Issued(token, discarded)
            }
        }
        return when (decision) {
            is IssueDecision.Issued -> {
                disposeAll(decision.discarded)
                ContinuationIssueResult.Issued(decision.token)
            }
            is IssueDecision.Rejected -> {
                stateDisposer.dispose(decision.state)
                ContinuationIssueResult.Rejected(ContinuationAccessFailure.StoreClosed)
            }
        }
    }

    fun <Output> lease(
        token: Token,
        query: Query,
        action: (State) -> Output,
    ): ContinuationLeaseResult<Output> = when (val claim = claim(token, query)) {
        is ClaimDecision.Rejected -> ContinuationLeaseResult.Rejected(claim.failure)
        is ClaimDecision.Discarded -> {
            stateDisposer.dispose(claim.entry.state)
            ContinuationLeaseResult.Rejected(claim.failure)
        }
        is ClaimDecision.Claimed -> leaseClaim(claim.token, claim.entry, action)
    }

    fun <Output> consume(
        token: Token,
        query: Query,
        action: (State) -> ContinuationTransition<Output, Query>,
    ): ContinuationConsumeResult<Token, Output> = when (val claim = claim(token, query)) {
        is ClaimDecision.Rejected -> ContinuationConsumeResult.Rejected(claim.failure)
        is ClaimDecision.Discarded -> {
            stateDisposer.dispose(claim.entry.state)
            ContinuationConsumeResult.Rejected(claim.failure)
        }
        is ClaimDecision.Claimed -> consumeClaim(claim.token, claim.entry.state, action)
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
                        InvalidationDecision.Discarded(entry.state, ContinuationAccessFailure.ExpiredToken)
                    } else {
                        scheduleNextExpiryLocked(clock.nowNanos())
                        InvalidationDecision.Discarded(entry.state, null)
                    }
                }
            }
        }
        return when (decision) {
            is InvalidationDecision.Rejected -> ContinuationInvalidationResult.Rejected(decision.failure)
            is InvalidationDecision.Discarded -> {
                stateDisposer.dispose(decision.state)
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
            while (activeCallbacks > 0) callbacksDrained.awaitUninterruptibly()
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
            ClaimDecision.Discarded(entry, failure)
        } else {
            check(inFlightTokens.add(token)) { "Continuation token was already in flight" }
            activeCallbacks = Math.addExact(activeCallbacks, 1)
            ClaimDecision.Claimed(token, entry)
        }
    }

    private fun <Output> leaseClaim(
        token: Token,
        entry: Entry<Query, State>,
        action: (State) -> Output,
    ): ContinuationLeaseResult<Output> {
        val output = try {
            action(entry.state)
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
                entries[token] = entry
                val evicted = removeOverCapacityLocked()
                scheduleNextExpiryLocked(clock.nowNanos())
                LeaseRetention.Retained(evicted)
            }
        }
        val disposeFailure = when (retainDecision) {
            LeaseRetention.Terminal -> disposeCapturingFailure(entry.state)
            is LeaseRetention.Retained -> disposeAllCapturingFailure(retainDecision.evicted)
        }
        finishCallback(token, disposeFailure)
        disposeFailure?.let { throw it }
        return ContinuationLeaseResult.Granted(output)
    }

    private fun <Output> consumeClaim(
        token: Token,
        state: State,
        action: (State) -> ContinuationTransition<Output, Query>,
    ): ContinuationConsumeResult<Token, Output> {
        val transition = try {
            action(state)
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

    private fun <Output> reissueClaim(
        claimedToken: Token,
        state: State,
        transition: ContinuationTransition.Reissue<Output, Query>,
    ): ContinuationConsumeResult<Token, Output> {
        val decision = lock.withLock {
            if (closing) {
                ReissueDecision.Terminal
            } else {
                val token = tokenIssuer.issue()
                check(token !in inFlightTokens) {
                    "Continuation token issuer returned a token owned by an in-flight callback"
                }
                val nowNanos = clock.nowNanos()
                val discarded = removeExpiredLocked(nowNanos).toMutableList()
                entries.remove(token)?.let { replaced -> discarded += replaced.state }
                entries[token] = Entry(transition.nextQuery, state, nowNanos)
                discarded += removeOverCapacityLocked()
                scheduleNextExpiryLocked(nowNanos)
                ReissueDecision.Reissued(token, discarded)
            }
        }
        return when (decision) {
            ReissueDecision.Terminal -> {
                val disposeFailure = disposeCapturingFailure(state)
                finishCallback(claimedToken, disposeFailure)
                disposeFailure?.let { throw it }
                ContinuationConsumeResult.Rejected(ContinuationAccessFailure.StoreClosed)
            }
            is ReissueDecision.Reissued -> {
                val disposeFailure = disposeAllCapturingFailure(decision.discarded)
                finishCallback(claimedToken, disposeFailure)
                disposeFailure?.let { throw it }
                ContinuationConsumeResult.Reissued(transition.output, decision.token)
            }
        }
    }

    private fun finishCallback(token: Token, disposeFailure: Throwable?) {
        lock.withLock {
            check(inFlightTokens.remove(token)) { "Continuation callback token was not in flight" }
            activeCallbacks = Math.subtractExact(activeCallbacks, 1)
            if (closing) recordCloseFailureLocked(disposeFailure)
            if (activeCallbacks == 0) callbacksDrained.signalAll()
        }
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

    private fun removeOverCapacityLocked(): List<State> = buildList {
        while (entries.size > capacity.value) {
            val iterator = entries.entries.iterator()
            add(iterator.next().value.state)
            iterator.remove()
        }
    }

    private fun scheduleNextExpiryLocked(nowNanos: Long) {
        expiryTask?.cancel(false)
        expiryTask = null
        if (closing || entries.isEmpty()) return
        val earliestCreatedAt = entries.values.minOf(Entry<Query, State>::createdAtNanos)
        val elapsedNanos = nowNanos - earliestCreatedAt
        val delayNanos = (timeToLive.nanoseconds - elapsedNanos).coerceAtLeast(0L)
        expiryTask = EXPIRY_EXECUTOR.schedule(
            PassiveExpiryTask(this),
            delayNanos,
            TimeUnit.NANOSECONDS,
        )
    }

    private fun expirePassively() {
        val expired = lock.withLock {
            expiryTask = null
            if (closing) {
                emptyList()
            } else {
                val nowNanos = clock.nowNanos()
                removeExpiredLocked(nowNanos).also { scheduleNextExpiryLocked(nowNanos) }
            }
        }
        disposeAllCapturingFailure(expired)
    }

    private fun elapsedNanos(createdAtNanos: Long): Long = clock.nowNanos() - createdAtNanos

    private fun disposeAll(states: List<State>) {
        disposeAllCapturingFailure(states)?.let { throw it }
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

    private sealed interface IssueDecision<out Token, out State> {
        data class Issued<Token, State>(
            val token: Token,
            val discarded: List<State>,
        ) : IssueDecision<Token, State>

        data class Rejected<State>(val state: State) : IssueDecision<Nothing, State>
    }

    private sealed interface ClaimDecision<out Token, out Query, out State> {
        data class Claimed<Token, Query, State>(
            val token: Token,
            val entry: Entry<Query, State>,
        ) : ClaimDecision<Token, Query, State>

        data class Discarded<Query, State>(
            val entry: Entry<Query, State>,
            val failure: ContinuationAccessFailure,
        ) : ClaimDecision<Nothing, Query, State>

        data class Rejected(
            val failure: ContinuationAccessFailure,
        ) : ClaimDecision<Nothing, Nothing, Nothing>
    }

    private sealed interface LeaseRetention<out State> {
        data object Terminal : LeaseRetention<Nothing>

        data class Retained<State>(val evicted: List<State>) : LeaseRetention<State>
    }

    private sealed interface ReissueDecision<out Token, out State> {
        data object Terminal : ReissueDecision<Nothing, Nothing>

        data class Reissued<Token, State>(
            val token: Token,
            val discarded: List<State>,
        ) : ReissueDecision<Token, State>
    }

    private sealed interface InvalidationDecision<out State> {
        data class Discarded<State>(
            val state: State,
            val failure: ContinuationAccessFailure?,
        ) : InvalidationDecision<State>

        data class Rejected(
            val failure: ContinuationAccessFailure,
        ) : InvalidationDecision<Nothing>
    }

    private class PassiveExpiryTask<Token : Any, Query : Any, State : Any>(
        store: ServerHeldContinuationStore<Token, Query, State>,
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
