@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.workspace.intellij.read.hosted

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HostedQueryExecutorTest {
    @Test
    fun `success and platform failure preserve the last bounded stage`() = runTest {
        val executor = HostedQueryExecutor(backgroundScope)
        assertEquals(
            HostedExecution.Completed(42, HostedQueryStage.RESULT_DETACHED),
            executor.execute(executor.endpoint) { progress ->
                progress.advance(HostedQueryStage.RESULT_DETACHED)
                42
            },
        )
        assertEquals(
            HostedExecution.Rejected(
                HostedQueryFailure.Platform(HostedPlatformFailureCause.RUNTIME),
                HostedQueryStage.SEMANTIC_READ,
            ),
            executor.execute<Int>(executor.endpoint) { progress ->
                progress.advance(HostedQueryStage.SEMANTIC_READ)
                throw IllegalStateException("Must not escape into result evidence")
            },
        )
        executor.retire()
        executor.drain()
    }

    @Test
    fun `deadline cancels work and drains its finally before admitting the next request`() = runTest {
        val executor = HostedQueryExecutor(backgroundScope)
        val cleanup = CompletableDeferred<Unit>()
        val first = async {
            executor.execute(executor.endpoint) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { cleanup.await() }
                }
            }
        }
        runCurrent()
        advanceTimeBy(HOSTED_QUERY_BUDGET_MILLIS)
        runCurrent()
        assertFalse(first.isCompleted)
        assertEquals(HostedExecution.Rejected(HostedQueryFailure.BUSY), executor.execute(executor.endpoint) { 1 })
        cleanup.complete(Unit)
        runCurrent()
        assertEquals(HostedExecution.Rejected(HostedQueryFailure.BUDGET_EXCEEDED), first.await())
        assertEquals(HostedExecution.Completed(2), executor.execute(executor.endpoint) { 2 })
        executor.retire()
        executor.drain()
    }

    @Test
    fun `retirement invalidates an in-flight detached result and waits for owned cleanup`() = runTest {
        val executor = HostedQueryExecutor(backgroundScope)
        val cleanup = CompletableDeferred<Unit>()
        val work = async {
            executor.execute(executor.endpoint) { progress ->
                progress.advance(HostedQueryStage.CONTENT_REVALIDATION)
                withContext(NonCancellable) { cleanup.await() }
                42
            }
        }
        runCurrent()
        executor.retire()
        val retired = async { executor.drain() }
        runCurrent()
        assertFalse(retired.isCompleted)
        cleanup.complete(Unit)
        runCurrent()
        assertEquals(
            HostedExecution.Rejected(HostedQueryFailure.RETIRED, HostedQueryStage.CONTENT_REVALIDATION),
            work.await(),
        )
        retired.await()
        assertEquals(HostedExecution.Rejected(HostedQueryFailure.RETIRED), executor.execute(executor.endpoint) { 1 })
    }

    @Test
    fun `caller cancellation drains owned analysis before releasing admission`() = runTest {
        val executor = HostedQueryExecutor(backgroundScope)
        val cleanup = CompletableDeferred<Unit>()
        val first = async {
            executor.execute(executor.endpoint) {
                try {
                    delay(100_000)
                    1
                } finally {
                    withContext(NonCancellable) { cleanup.await() }
                }
            }
        }
        runCurrent()
        first.cancel()
        runCurrent()
        assertEquals(HostedExecution.Rejected(HostedQueryFailure.BUSY), executor.execute(executor.endpoint) { 2 })
        cleanup.complete(Unit)
        runCurrent()
        first.join()
        assertEquals(HostedExecution.Completed(3), executor.execute(executor.endpoint) { 3 })
        executor.retire()
        executor.drain()
    }
}
