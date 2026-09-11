package io.github.amichne.kast.indexer

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.runtime.composition.KastRuntimeDispatch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

internal sealed interface IndexerSemanticExecution {
    class Completed(val dispatch: KastRuntimeDispatch) : IndexerSemanticExecution

    data object Busy : IndexerSemanticExecution

    data object DeadlineExceeded : IndexerSemanticExecution

    data object Cancelled : IndexerSemanticExecution

    data object Rejected : IndexerSemanticExecution

    data object RecoveryRequired : IndexerSemanticExecution
}

internal enum class IndexerSemanticRetirement {
    PROVEN,
    RECOVERY_REQUIRED,
}

/** One workspace owns one semantic job. Cancellation alone is never proof that the lane is free. */
internal class IndexerSemanticLane(
    private val host: KastIndexerHost,
    private val policy: IndexerRequestPolicy,
    private val activity: IndexerRequestActivitySink,
) {
    private val dispatcher =
        Executors.newSingleThreadExecutor(
                Thread.ofPlatform()
                    .name("kast-indexer-semantic")
                    .daemon(true)
                    .inheritInheritableThreadLocals(false)
                    .factory()
            )
            .asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val state = AtomicReference<State>(State.Ready)

    private sealed interface State {
        data object Ready : State

        class Active(val job: Deferred<KastRuntimeDispatch>, val completion: CountDownLatch) : State

        class RecoveryRequired(val active: Active) : State

        data object Closed : State
    }

    fun dispatch(document: String, peer: IndexerRequestPeer = IndexerRequestPeer.Unobserved): IndexerSemanticExecution {
        val job = scope.async(start = CoroutineStart.LAZY) { host.dispatch(document) }
        val active = State.Active(job, CountDownLatch(1))
        job.invokeOnCompletion { active.completion.countDown() }
        if (!state.compareAndSet(State.Ready, active)) {
            job.cancel()
            return when (state.get()) {
                is State.RecoveryRequired -> {
                    observe(IndexerRequestStage.SEMANTIC_ADMISSION, IndexerRequestOutcome.RECOVERY_REQUIRED)
                    IndexerSemanticExecution.RecoveryRequired
                }
                State.Closed -> IndexerSemanticExecution.Cancelled
                is State.Active,
                State.Ready -> {
                    observe(IndexerRequestStage.SEMANTIC_ADMISSION, IndexerRequestOutcome.CAPACITY_EXCEEDED)
                    IndexerSemanticExecution.Busy
                }
            }
        }
        observe(IndexerRequestStage.SEMANTIC_ADMISSION, IndexerRequestOutcome.COMPLETED)
        observe(IndexerRequestStage.DISPATCH, IndexerRequestOutcome.STARTED)
        job.start()
        val completion = await(active.completion, policy.dispatch, peer)
        if (completion != Completion.PROVEN) {
            val outcome =
                when (completion) {
                    Completion.EXPIRED -> IndexerRequestOutcome.DEADLINE_EXCEEDED
                    Completion.INTERRUPTED -> IndexerRequestOutcome.CANCELLED
                    Completion.PROVEN -> error("Unreachable completed request")
                }
            observe(IndexerRequestStage.DISPATCH, outcome)
            job.cancel(CancellationException("indexer request lifetime ended"))
            val retired = retire(active)
            if (retired == IndexerSemanticRetirement.RECOVERY_REQUIRED) return IndexerSemanticExecution.RecoveryRequired
            state.compareAndSet(active, State.Ready)
            return when (completion) {
                Completion.EXPIRED -> IndexerSemanticExecution.DeadlineExceeded
                Completion.INTERRUPTED -> IndexerSemanticExecution.Cancelled
                Completion.PROVEN -> error("Unreachable completed request")
            }
        }
        return try {
            // The completion callback proves this await cannot wait for unfinished semantic work.
            val result = runBlocking { job.await() }
            observe(
                IndexerRequestStage.DISPATCH,
                when (result) {
                    is KastRuntimeDispatch.Responded -> IndexerRequestOutcome.COMPLETED
                    is KastRuntimeDispatch.Rejected -> IndexerRequestOutcome.REJECTED
                },
            )
            IndexerSemanticExecution.Completed(result)
        } catch (_: CancellationException) {
            observe(IndexerRequestStage.DISPATCH, IndexerRequestOutcome.CANCELLED)
            IndexerSemanticExecution.Cancelled
        } catch (_: Exception) {
            observe(IndexerRequestStage.DISPATCH, IndexerRequestOutcome.REJECTED)
            IndexerSemanticExecution.Rejected
        } finally {
            state.compareAndSet(active, State.Ready)
        }
    }

    fun close(): IndexerSemanticRetirement {
        val previous = state.getAndSet(State.Closed)
        val active =
            when (previous) {
                State.Ready,
                State.Closed -> {
                    scope.cancel()
                    dispatcher.close()
                    return IndexerSemanticRetirement.PROVEN
                }
                is State.Active -> previous
                is State.RecoveryRequired -> previous.active
            }
        active.job.cancel(CancellationException("indexer transport closed"))
        val retirement = retire(active)
        scope.cancel()
        if (retirement == IndexerSemanticRetirement.PROVEN) dispatcher.close()
        else active.job.invokeOnCompletion { dispatcher.close() }
        return retirement
    }

    private fun retire(active: State.Active): IndexerSemanticRetirement {
        observe(IndexerRequestStage.RETIREMENT, IndexerRequestOutcome.STARTED)
        val interrupted = Thread.interrupted()
        val retired =
            try {
                await(active.completion, policy.retirement) == Completion.PROVEN
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        if (retired) {
            observe(IndexerRequestStage.RETIREMENT, IndexerRequestOutcome.COMPLETED)
            return IndexerSemanticRetirement.PROVEN
        }
        state.updateAndGet { previous ->
            when (previous) {
                active,
                State.Closed -> State.RecoveryRequired(active)
                else -> previous
            }
        }
        observe(IndexerRequestStage.RETIREMENT, IndexerRequestOutcome.RECOVERY_REQUIRED)
        return IndexerSemanticRetirement.RECOVERY_REQUIRED
    }

    private fun observe(stage: IndexerRequestStage, outcome: IndexerRequestOutcome) =
        activity.observe(IndexerRequestActivity(stage, outcome))
}

private enum class Completion {
    PROVEN,
    EXPIRED,
    INTERRUPTED,
}

private fun await(
    completion: CountDownLatch,
    limit: ElapsedTimeLimitMillis,
    peer: IndexerRequestPeer = IndexerRequestPeer.Unobserved,
): Completion {
    return try {
        val started = System.nanoTime()
        while (completion.count != 0L) {
            if (peer.observe() != IndexerPeerState.CONNECTED) return Completion.INTERRUPTED
            val remaining = limit.value - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            if (remaining <= 0) return Completion.EXPIRED
            completion.await(minOf(remaining, 25L), TimeUnit.MILLISECONDS)
        }
        Completion.PROVEN
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        Completion.INTERRUPTED
    }
}
