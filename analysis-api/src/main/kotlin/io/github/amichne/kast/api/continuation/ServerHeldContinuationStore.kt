package io.github.amichne.kast.api.continuation

import java.lang.ref.WeakReference
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ServerHeldContinuationStore<
    Token : Any,
    Query : Any,
    State : ContinuationOwnedState,
    Projection : ContinuationProjection,
>(
    private val capacity: ContinuationCapacity,
    private val timeToLive: ContinuationTtl,
    private val tokenIssuer: ContinuationTokenIssuer<Token>,
    private val stateDisposer: ContinuationStateDisposer<State>,
    private val clock: ContinuationClock = ContinuationClock.System,
) : AutoCloseable {
    private val lock = ReentrantLock()
    private val callbacksDrained = lock.newCondition()
    private val entries = LinkedHashMap<Token, Entry<Query, State>>()
    private val inFlightTokens = mutableMapOf<Token, Thread>()
    private val disposalThreads = mutableMapOf<Thread, Int>()
    private var expiryTask: ScheduledFuture<*>? = null
    private var activeCallbacks = 0
    private var activeDisposals = 0
    private var publicationInProgress = false
    private var publicationOwner: Thread? = null
    private var closing = false
    private var closeCompleted = false
    private var closeFailure: Throwable? = null

    fun issue(query: Query, state: State): ContinuationIssueResult<Token> {
        val preparation = lock.withLock {
            awaitPublicationLocked()
            if (closing) {
                IssuePreparation.Rejected(
                    disposal = registerStateDisposalLocked(state),
                    failure = ContinuationAccessFailure.StoreClosed,
                )
            } else {
                val token = try {
                    tokenIssuer.issue()
                } catch (failure: Throwable) {
                    return@withLock IssuePreparation.IssuerFailed(
                        disposal = registerStateDisposalLocked(state),
                        failure = failure,
                    )
                }
                if (token in inFlightTokens) {
                    return@withLock IssuePreparation.Rejected(
                        disposal = registerStateDisposalLocked(state),
                        failure = ContinuationAccessFailure.TokenCollision,
                    )
                }
                val nowNanos = clock.nowNanos()
                val discarded = removeExpiredLocked(nowNanos).toMutableList()
                entries.remove(token)?.let { replaced -> discarded += replaced.state }
                discarded += removeForPublicationCapacityLocked(nowNanos)
                scheduleNextExpiryLocked(nowNanos)
                startPublicationLocked()
                IssuePreparation.Prepared(
                    token = token,
                    entry = Entry(query, state, nowNanos),
                    disposal = registerDisposalLocked(discarded),
                )
            }
        }
        return when (preparation) {
            is IssuePreparation.Prepared -> completeIssuePublication(preparation)
            is IssuePreparation.Rejected -> {
                disposeRegistered(preparation.disposal)
                ContinuationIssueResult.Rejected(preparation.failure)
            }
            is IssuePreparation.IssuerFailed -> {
                val disposeFailure = disposeRegisteredCapturingFailure(preparation.disposal)
                disposeFailure?.let(preparation.failure::addSuppressed)
                throw preparation.failure
            }
        }
    }

    private fun completeIssuePublication(
        preparation: IssuePreparation.Prepared<Token, Query, State>,
    ): ContinuationIssueResult<Token> {
        val publicationFailure = disposeRegisteredCapturingFailure(preparation.disposal)
        if (publicationFailure != null) {
            rollbackIssuePublication(preparation.entry.state, publicationFailure)
        }

        val terminalDisposal = lock.withLock {
            if (closing) {
                registerStateDisposalLocked(preparation.entry.state).also {
                    finishPublicationLocked()
                }
            } else {
                entries[preparation.token] = preparation.entry
                scheduleNextExpiryLocked(clock.nowNanos())
                finishPublicationLocked()
                null
            }
        }
        if (terminalDisposal != null) {
            disposeRegistered(terminalDisposal)
            return ContinuationIssueResult.Rejected(ContinuationAccessFailure.StoreClosed)
        }
        return ContinuationIssueResult.Issued(preparation.token)
    }

    private fun rollbackIssuePublication(state: State, publicationFailure: Throwable): Nothing {
        val rollbackDisposal = lock.withLock {
            registerStateDisposalLocked(state).also {
                scheduleNextExpiryLocked(clock.nowNanos())
                finishPublicationLocked()
            }
        }
        disposeRegisteredCapturingFailure(rollbackDisposal)?.let(publicationFailure::addSuppressed)
        throw publicationFailure
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
        transition: ContinuationStateTransition<State, Projection, Query>,
    ): ContinuationConsumeResult<Token, Projection> = when (val claim = claim(token, query)) {
        is ClaimDecision.Rejected -> ContinuationConsumeResult.Rejected(claim.failure)
        is ClaimDecision.Discarded -> {
            disposeRegistered(claim.disposal)
            ContinuationConsumeResult.Rejected(claim.failure)
        }
        is ClaimDecision.Claimed -> consumeClaim(claim.token, claim.entry.state, transition)
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
                null
            } else {
                closing = true
                expiryTask?.cancel(false)
                expiryTask = null
                callbacksDrained.signalAll()
                registerDisposalLocked(
                    entries.values.map(Entry<Query, State>::state).also { entries.clear() },
                )
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
            check(inFlightTokens.putIfAbsent(token, Thread.currentThread()) == null) {
                "Continuation token was already in flight"
            }
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
            awaitPublicationLocked()
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
        transition: ContinuationStateTransition<State, Projection, Query>,
    ): ContinuationConsumeResult<Token, Projection> {
        val result = try {
            transition.transition(state)
        } catch (failure: Throwable) {
            val disposeFailure = disposeCapturingFailure(state)
            finishCallback(token, disposeFailure)
            disposeFailure?.let(failure::addSuppressed)
            throw failure
        }

        return when (result) {
            is ContinuationTransition.Complete -> {
                val disposeFailure = disposeCapturingFailure(state)
                finishCallback(token, disposeFailure)
                disposeFailure?.let { throw it }
                ContinuationConsumeResult.Completed(result.output)
            }
            is ContinuationTransition.Reissue -> reissueClaim(token, state, result)
        }
    }

    private fun reissueClaim(
        claimedToken: Token,
        state: State,
        transition: ContinuationTransition.Reissue<Projection, Query>,
    ): ContinuationConsumeResult<Token, Projection> {
        val preparation = lock.withLock {
            awaitPublicationLocked()
            if (closing) {
                ReissuePreparation.Terminal
            } else {
                val token = try {
                    tokenIssuer.issue()
                } catch (failure: Throwable) {
                    return@withLock ReissuePreparation.IssuerFailed(failure)
                }
                if (token in inFlightTokens) {
                    return@withLock ReissuePreparation.Rejected(
                        ContinuationAccessFailure.TokenCollision,
                    )
                }
                val nowNanos = clock.nowNanos()
                val discarded = removeExpiredLocked(nowNanos).toMutableList()
                entries.remove(token)?.let { replaced -> discarded += replaced.state }
                discarded += removeForPublicationCapacityLocked(nowNanos)
                scheduleNextExpiryLocked(nowNanos)
                startPublicationLocked()
                ReissuePreparation.Prepared(
                    token = token,
                    entry = Entry(transition.nextQuery, state, nowNanos),
                    disposal = registerDisposalLocked(discarded),
                )
            }
        }
        return when (preparation) {
            is ReissuePreparation.Prepared -> completeReissuePublication(
                claimedToken,
                transition.output,
                preparation,
            )
            ReissuePreparation.Terminal -> {
                val disposeFailure = disposeCapturingFailure(state)
                finishCallback(claimedToken, disposeFailure)
                disposeFailure?.let { throw it }
                ContinuationConsumeResult.Rejected(ContinuationAccessFailure.StoreClosed)
            }
            is ReissuePreparation.Rejected -> {
                val disposeFailure = disposeCapturingFailure(state)
                finishCallback(claimedToken, disposeFailure)
                disposeFailure?.let { throw it }
                ContinuationConsumeResult.Rejected(preparation.failure)
            }
            is ReissuePreparation.IssuerFailed -> {
                val disposeFailure = disposeCapturingFailure(state)
                finishCallback(claimedToken, disposeFailure)
                disposeFailure?.let(preparation.failure::addSuppressed)
                throw preparation.failure
            }
        }
    }

    private fun completeReissuePublication(
        claimedToken: Token,
        output: Projection,
        preparation: ReissuePreparation.Prepared<Token, Query, State>,
    ): ContinuationConsumeResult<Token, Projection> {
        val publicationFailure = disposeRegisteredCapturingFailure(preparation.disposal)
        if (publicationFailure != null) {
            rollbackReissuePublication(claimedToken, preparation.entry.state, publicationFailure)
        }

        val terminalDisposal = lock.withLock {
            if (closing) {
                registerStateDisposalLocked(preparation.entry.state).also {
                    finishPublicationLocked()
                }
            } else {
                finishCallbackLocked(claimedToken, null)
                entries[preparation.token] = preparation.entry
                scheduleNextExpiryLocked(clock.nowNanos())
                finishPublicationLocked()
                null
            }
        }
        if (terminalDisposal != null) {
            val disposeFailure = disposeRegisteredCapturingFailure(terminalDisposal)
            finishCallback(claimedToken, disposeFailure)
            disposeFailure?.let { throw it }
            return ContinuationConsumeResult.Rejected(ContinuationAccessFailure.StoreClosed)
        }
        return ContinuationConsumeResult.Reissued(output, preparation.token)
    }

    private fun rollbackReissuePublication(
        claimedToken: Token,
        state: State,
        publicationFailure: Throwable,
    ): Nothing {
        val rollbackDisposal = lock.withLock {
            registerStateDisposalLocked(state).also {
                scheduleNextExpiryLocked(clock.nowNanos())
                finishPublicationLocked()
            }
        }
        val rollbackFailure = disposeRegisteredCapturingFailure(rollbackDisposal)
        finishCallback(claimedToken, rollbackFailure)
        rollbackFailure?.let(publicationFailure::addSuppressed)
        throw publicationFailure
    }

    private fun finishCallback(token: Token, disposeFailure: Throwable?) {
        lock.withLock {
            finishCallbackLocked(token, disposeFailure)
        }
    }

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
            add(removeOldestLocked(nowNanos))
        }
    }

    private fun removeForPublicationCapacityLocked(nowNanos: Long): List<State> = buildList {
        while (entries.size >= capacity.value) {
            add(removeOldestLocked(nowNanos))
        }
    }

    private fun removeOldestLocked(nowNanos: Long): State {
        val oldest = entries.entries.maxBy { (_, entry) -> nowNanos - entry.createdAtNanos }
        entries.remove(oldest.key)
        return oldest.value.state
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

    private sealed interface IssuePreparation<out Token, out Query, out State> {
        data class Prepared<Token, Query, State>(
            val token: Token,
            val entry: Entry<Query, State>,
            val disposal: RegisteredDisposal<State>?,
        ) : IssuePreparation<Token, Query, State>

        data class Rejected<State>(
            val disposal: RegisteredDisposal<State>,
            val failure: ContinuationAccessFailure,
        ) : IssuePreparation<Nothing, Nothing, State>

        data class IssuerFailed<State>(
            val disposal: RegisteredDisposal<State>,
            val failure: Throwable,
        ) : IssuePreparation<Nothing, Nothing, State>
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

    private sealed interface ReissuePreparation<out Token, out Query, out State> {
        data object Terminal : ReissuePreparation<Nothing, Nothing, Nothing>

        data class Prepared<Token, Query, State>(
            val token: Token,
            val entry: Entry<Query, State>,
            val disposal: RegisteredDisposal<State>?,
        ) : ReissuePreparation<Token, Query, State>

        data class Rejected(
            val failure: ContinuationAccessFailure,
        ) : ReissuePreparation<Nothing, Nothing, Nothing>

        data class IssuerFailed(
            val failure: Throwable,
        ) : ReissuePreparation<Nothing, Nothing, Nothing>
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

    private class PassiveExpiryTask<
        Token : Any,
        Query : Any,
        State : ContinuationOwnedState,
        Projection : ContinuationProjection,
    >(
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
