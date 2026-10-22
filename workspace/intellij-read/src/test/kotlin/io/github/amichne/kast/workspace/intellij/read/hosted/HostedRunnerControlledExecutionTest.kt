@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RequestedExecutionBudget
import io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedRunnerControlledExecutionTest {
    @Test
    fun `runner-controlled semantic work exceeds host deadline and preserves typed output grant`() = runTest {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val executor =
            HostedQueryExecutor(backgroundScope, { testScheduler.currentTime * 1_000_000L }) { limits ->
                HostedReadDiagnostics({ testScheduler.currentTime * 1_000_000L }, limits, publish = receipts::add)
            }
        try {
            val result =
                executor.execute(
                    executor.endpoint,
                    shortHostLimits(),
                    executionBudget = HostedExecutionBudgetRequest(RequestedExecutionBudget.RunnerControlled()),
                    completion = HostedReadCompletionPolicy.CALLER_ELAPSED,
                ) { progress ->
                    runHostedReadTransaction(progress, { Refinement.Refined(Unit) }) { allowance ->
                        assertInstanceOf(HostedSemanticTimeAllowance.RunnerControlled::class.java, allowance)
                        delay(20_000L)
                        42
                    }
                }
            val completed = assertInstanceOf(HostedExecution.Completed::class.java, result)
            assertEquals(HostedSemanticRead.Resolved(42), completed.value)
            val present = assertInstanceOf(ExecutionBudgetPresence.Present::class.java, completed.executionBudget)
            assertInstanceOf(ExecutionBudgetReport.RunnerControlled::class.java, present.report)
            assertEquals(HostedSemanticBudgetObservation.RunnerControlled, receipts.single().semanticBudget)
            assertEquals(HostedSemanticEntry.RunnerControlled, receipts.single().semanticEntry)
            assertEquals(20_000L, testScheduler.currentTime)
        } finally {
            executor.retire()
            executor.drain()
        }
    }

    @Test
    fun `runner-controlled semantic readmission does not restore an expired host deadline`() {
        var now = 0L
        val deadline =
            HostedReadDeadline(
                shortHostLimits(),
                { now },
                HostedExecutionBudgetRequest(RequestedExecutionBudget.RunnerControlled()),
            )
        assertInstanceOf(Refinement.Refined::class.java, deadline.admit(null))
        now = 20_000_000_000L
        val result = assertInstanceOf(Refinement.Refined::class.java, deadline.admit(null))
        assertInstanceOf(HostedSemanticTimeAllowance.RunnerControlled::class.java, result.value)
    }

    @Test
    fun `runner-controlled preparation has no implicit host deadline`() = runTest {
        val executor = HostedQueryExecutor(backgroundScope)
        try {
            val result =
                executor.execute(
                    executor.endpoint,
                    shortHostLimits(),
                    executionBudget = HostedExecutionBudgetRequest(RequestedExecutionBudget.RunnerControlled()),
                ) {
                    delay(20_000L)
                }
            assertEquals(HostedExecution.Completed(Unit), result)
            assertEquals(20_000L, testScheduler.currentTime)
        } finally {
            executor.retire()
            executor.drain()
        }
    }

    @Test
    fun `only caller timeout bounds preparation without a hidden host clamp`() = runTest {
        for (timeout in listOf(100L, 10_000L)) {
            val executor = HostedQueryExecutor(backgroundScope, { testScheduler.currentTime * 1_000_000L })
            val request =
                RequestedExecutionBudget.RunnerControlled(
                    timeout = (ElapsedTimeLimitMillis.parse(timeout) as Refinement.Refined).value
                )
            try {
                val result =
                    executor.execute(
                        executor.endpoint,
                        shortHostLimits(),
                        executionBudget = HostedExecutionBudgetRequest(request),
                    ) { progress ->
                        progress.advance(HostedQueryStage.MODEL_CAPTURE)
                        delay(5_000L)
                        42
                    }
                val expected =
                    if (timeout == 100L)
                        HostedExecution.Rejected(HostedQueryFailure.CALLER_TIMEOUT, HostedQueryStage.MODEL_CAPTURE)
                    else HostedExecution.Completed(42, HostedQueryStage.MODEL_CAPTURE)
                assertEquals(expected, result)
            } finally {
                executor.retire()
                executor.drain()
            }
        }
    }

    @Test
    fun `cancellation after evaluation discards staged publication before commit`() = runTest {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val publication = mutableListOf<String>()
        val executor =
            HostedQueryExecutor(backgroundScope) { limits ->
                HostedReadDiagnostics({ testScheduler.currentTime * 1_000_000L }, limits, publish = receipts::add)
            }
        val work = async {
            val caller = requireNotNull(coroutineContext[Job])
            executor.execute(
                executor.endpoint,
                executionBudget = HostedExecutionBudgetRequest(RequestedExecutionBudget.RunnerControlled()),
                outcome = {
                    caller.cancel()
                    HostedDiagnosticOutcome.Completed
                },
            ) { progress ->
                runHostedReadTransaction(progress, { Refinement.Refined(Unit) }) {
                    progress.publicationEffects.prepare(recordingPublication(publication))
                    42
                }
            }
        }
        try {
            work.join()
            assertEquals(listOf("discard"), publication)
            assertEquals(HostedDiagnosticOutcome.Rejected(HostedQueryFailure.CANCELLED), receipts.single().outcome)
        } finally {
            executor.retire()
            executor.drain()
        }
    }

    @Test
    fun `runner cancellation drains native work before it releases admission and publishes no success`() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val publication = mutableListOf<String>()
        val executor =
            HostedQueryExecutor(backgroundScope, { testScheduler.currentTime * 1_000_000L }) { limits ->
                HostedReadDiagnostics({ testScheduler.currentTime * 1_000_000L }, limits, publish = receipts::add)
            }
        val work = async {
            executor.execute(
                executor.endpoint,
                shortHostLimits(),
                executionBudget = HostedExecutionBudgetRequest(RequestedExecutionBudget.RunnerControlled()),
            ) { progress ->
                runHostedReadTransaction(progress, { Refinement.Refined(Unit) }) {
                    assertEquals(
                        Refinement.Refined(Unit),
                        progress.publicationEffects.prepare(recordingPublication(publication)),
                    )
                    holdReadUntilCancellation(entered, cleanup)
                }
            }
        }
        try {
            runCurrent()
            assertTrue(entered.isCompleted)
            advanceTimeBy(20_000L)
            runCurrent()
            assertFalse(work.isCompleted)
            work.cancel()
            runCurrent()
            assertFalse(work.isCompleted)
            assertTrue(receipts.isEmpty())
            assertTrue(publication.isEmpty())
            cleanup.complete(Unit)
            work.join()
            assertEquals(HostedDiagnosticOutcome.Rejected(HostedQueryFailure.CANCELLED), receipts.single().outcome)
            assertEquals(listOf("discard"), publication)
            assertEquals(HostedExecution.Completed(7), executor.execute(executor.endpoint) { 7 })
        } finally {
            cleanup.complete(Unit)
            executor.retire()
            executor.drain()
        }
    }
}

private suspend fun holdReadUntilCancellation(
    entered: CompletableDeferred<Unit>,
    cleanup: CompletableDeferred<Unit>,
): Nothing {
    entered.complete(Unit)
    try {
        awaitCancellation()
    } finally {
        withContext(NonCancellable) { cleanup.await() }
    }
}

private fun recordingPublication(publication: MutableList<String>): HostedReadPublicationEffect =
    object : HostedReadPublicationEffect {
        override fun commit(): Refinement<Unit, HostedQueryFailure> {
            publication += "commit"
            return Refinement.Refined(Unit)
        }

        override fun discard() {
            publication += "discard"
        }
    }
