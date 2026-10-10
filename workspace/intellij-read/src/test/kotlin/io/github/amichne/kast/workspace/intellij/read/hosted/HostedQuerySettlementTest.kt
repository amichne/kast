@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.contract.WorkspaceNativeReadSettlement
import io.github.amichne.kast.workspace.contract.WorkspaceReadOperationIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedQuerySettlementTest {
    @Test
    fun `native settlement observation remains pending through deadline and retirement drainage`() = runTest {
        val executor = HostedQueryExecutor(backgroundScope, { testScheduler.currentTime * 1_000_000 })
        val cleanup = CompletableDeferred<Unit>()
        lateinit var identity: HostedReadTraceIdentity
        val work = async {
            executor.execute(executor.endpoint, observeReadIdentity = { identity = it }) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { cleanup.await() }
                }
            }
        }
        try {
            runCurrent()
            val expected = listOf(WorkspaceReadOperationIdentity.Traced(identity.value))
            assertEquals(expected, (executor.settlement() as WorkspaceNativeReadSettlement.Running).operations)
            advanceTimeBy(HOSTED_QUERY_BUDGET_MILLIS)
            runCurrent()
            assertFalse(work.isCompleted)
            assertEquals(expected, (executor.settlement() as WorkspaceNativeReadSettlement.Running).operations)
            executor.retire()
            val drain = async { executor.drain() }
            runCurrent()
            assertFalse(drain.isCompleted)
            assertEquals(expected, (executor.settlement() as WorkspaceNativeReadSettlement.Retired).operations)
            cleanup.complete(Unit)
            work.await()
            drain.await()
            assertTrue((executor.settlement() as WorkspaceNativeReadSettlement.Retired).operations.isEmpty())
            assertEquals(
                HostedExecution.Rejected(HostedQueryFailure.RETIRED),
                executor.execute(executor.endpoint) { 7 },
            )
        } finally {
            cleanup.complete(Unit)
            executor.retire()
            executor.drain()
        }
    }

    @Test
    fun `caller cancellation observation preserves operation until native cleanup proves settlement`() = runTest {
        val executor = HostedQueryExecutor(backgroundScope)
        val cleanup = CompletableDeferred<Unit>()
        lateinit var identity: HostedReadTraceIdentity
        val work = async {
            executor.execute(executor.endpoint, observeReadIdentity = { identity = it }) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { cleanup.await() }
                }
            }
        }
        try {
            runCurrent()
            work.cancel()
            runCurrent()
            assertFalse(work.isCompleted)
            assertEquals(
                listOf(WorkspaceReadOperationIdentity.Traced(identity.value)),
                (executor.settlement() as WorkspaceNativeReadSettlement.Running).operations,
            )
            cleanup.complete(Unit)
            work.join()
            assertSame(WorkspaceNativeReadSettlement.Quiescent, executor.settlement())
        } finally {
            cleanup.complete(Unit)
            executor.retire()
            executor.drain()
        }
    }
}
