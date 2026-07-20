package io.github.amichne.kast.server.mutation

import io.github.amichne.kast.api.contract.mutation.KastMutationExecutionResult
import io.github.amichne.kast.api.contract.mutation.KastMutationIdempotencyKey
import io.github.amichne.kast.api.contract.mutation.KastMutationFailure
import io.github.amichne.kast.api.contract.mutation.KastWorkspaceTaskId
import io.github.amichne.kast.api.protocol.ApiErrorResponse
import io.github.amichne.kast.api.protocol.AnalysisException
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.server.mutation.coordination.MutationFinishBarrierRequest
import io.github.amichne.kast.server.mutation.coordination.MutationFinishBarrierState
import io.github.amichne.kast.server.mutation.coordination.MutationFinishCoordinationToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MutationCoordinatorTest {
    @Test
    fun `same task key and fingerprint join one server owned worker`() = runBlocking {
        val coordinator = MutationCoordinator(this)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var executions = 0

        val first = async {
            coordinator.execute(taskId, key, fingerprint) {
                executions++
                started.complete(Unit)
                release.await()
                outcome
            }
        }
        started.await()
        val joined = async { coordinator.execute(taskId, key, fingerprint) { error("must join") } }
        assertFalse(joined.isCompleted)
        release.complete(Unit)

        assertTrue(first.await() is KastMutationExecutionResult.Failed)
        assertTrue((joined.await() as KastMutationExecutionResult.Failed).deduplicated)
        assertTrue(executions == 1)
    }

    @Test
    fun `different payload conflicts with an existing task key`() = runBlocking {
        val coordinator = MutationCoordinator(this)
        coordinator.execute(taskId, key, fingerprint) { outcome }

        val failure = runCatching {
            coordinator.execute(taskId, key, MutationFingerprint("b".repeat(64))) { outcome }
        }.exceptionOrNull()

        assertTrue(failure is ConflictException)
    }

    @Test
    fun `workspace lane serializes different mutations`() = runBlocking {
        val coordinator = MutationCoordinator(this)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var active = 0
        var maximumActive = 0
        suspend fun execute(nextKey: String, started: CompletableDeferred<Unit>? = null) = coordinator.execute(
            taskId,
            KastMutationIdempotencyKey(nextKey),
            MutationFingerprint(nextKey.padEnd(64, '0')),
        ) {
            active++
            maximumActive = maxOf(maximumActive, active)
            started?.complete(Unit)
            if (started != null) releaseFirst.await()
            active--
            outcome
        }

        val first = async { execute("first", firstStarted) }
        firstStarted.await()
        val second = async { execute("second") }
        delay(25)
        assertFalse(second.isCompleted)
        releaseFirst.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, maximumActive)
    }

    @Test
    fun `cancelled waiter does not cancel worker and retry receives cached result`() = runBlocking {
        val coordinator = MutationCoordinator(this)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val waiter = async {
            coordinator.execute(taskId, key, fingerprint) {
                started.complete(Unit)
                release.await()
                outcome
            }
        }
        started.await()
        waiter.cancelAndJoin()
        release.complete(Unit)

        val retry = coordinator.execute(taskId, key, fingerprint) { error("must reuse") }

        assertTrue(retry is KastMutationExecutionResult.Failed)
        assertTrue(retry.deduplicated)
    }

    @Test
    fun `finish barrier rejects admission and drains active workers`() = runBlocking {
        val coordinator = MutationCoordinator(this)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val active = async {
            coordinator.execute(taskId, key, fingerprint) {
                started.complete(Unit)
                release.await()
                outcome
            }
        }
        started.await()
        val request = MutationFinishBarrierRequest(
            taskId,
            MutationFinishCoordinationToken("00000000-0000-0000-0000-000000000421"),
        )
        val barrier = async { coordinator.acquireFinishBarrier(request) }
        delay(25)
        assertFalse(barrier.isCompleted)
        val rejection = runCatching {
            coordinator.execute(
                taskId,
                KastMutationIdempotencyKey("after-finish"),
                MutationFingerprint("c".repeat(64)),
            ) { outcome }
        }.exceptionOrNull()
        assertEquals("TASK_FINISH_IN_PROGRESS", (rejection as AnalysisException).errorCode)
        release.complete(Unit)
        active.await()

        assertEquals(MutationFinishBarrierState.DRAINED, barrier.await().state)
    }

    private val taskId = KastWorkspaceTaskId("00000000-0000-0000-0000-000000000420")
    private val key = KastMutationIdempotencyKey("same-request")
    private val fingerprint = MutationFingerprint("a".repeat(64))
    private val outcome = MutationCoordinator.ExecutionOutcome.Failed(
        KastMutationFailure.Thrown(
            ApiErrorResponse(
                requestId = "request",
                code = "TEST",
                message = "expected",
                retryable = false,
            ),
        ),
    )
}
