@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class HostedDeadlineEvidenceTest {
    @Test
    fun `caller cancellation and owner retirement remain distinct terminal receipts`() = runTest {
        for (cancellation in Cancellation.entries) {
            val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
            val executor =
                HostedQueryExecutor(backgroundScope, { testScheduler.currentTime * 1_000_000L }) { policy ->
                    HostedReadDiagnostics({ testScheduler.currentTime * 1_000_000L }, policy, receipts::add)
                }
            val work = async {
                executor.execute(executor.endpoint) { progress ->
                    runHostedReadTransaction(progress, { Refinement.Refined(Unit) }) { awaitCancellation() }
                }
            }
            runCurrent()
            when (cancellation) {
                Cancellation.CALLER -> work.cancel()
                Cancellation.OWNER -> executor.retire()
            }
            work.join()
            assertEquals(HostedDiagnosticOutcome.Rejected(cancellation.failure), receipts.single().outcome)
            assertEquals(HostedQueryStage.SEMANTIC_READ, receipts.single().stages.last().stage)
            assertInstanceOf(HostedSemanticBudgetObservation.Admitted::class.java, receipts.single().semanticBudget)
            executor.retire()
            executor.drain()
        }
    }

    @Test
    fun `fractional elapsed milliseconds cannot consume the publication reserve`() {
        var now = 0L
        val deadline = HostedReadDeadline(ReadLimits.Default, { now })
        now = 3_749_000_000L
        val lastMillisecond = deadline.admit(null).proven()
        assertEquals(1L, lastMillisecond.semantic.value)
        now += 1L
        assertEquals(Refinement.Rejected(HostedQueryFailure.BUDGET_EXCEEDED), deadline.admit(null))
        now = 4_000_000_000L
        assertEquals(Refinement.Rejected(HostedQueryFailure.BUDGET_EXCEEDED), deadline.admit(null))
        assertEquals(1L, lastMillisecond.executionBudget.elapsed.effective.value)
    }

    @Test
    fun `hard deadline retains the actual cleanup overrun in bounded stage evidence`() = runTest {
        lateinit var admittedReport: ExecutionBudgetReport
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val executor =
            HostedQueryExecutor(backgroundScope, { testScheduler.currentTime * 1_000_000L }) { policy ->
                HostedReadDiagnostics({ testScheduler.currentTime * 1_000_000L }, policy, receipts::add)
            }
        val result =
            executor.execute(executor.endpoint) { progress ->
                runHostedReadTransaction(progress, { Refinement.Refined(Unit) }) { allowance ->
                    admittedReport = ExecutionBudgetReport.from(allowance.executionBudget)
                    assertEquals(2_000L, allowance.semantic.value)
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { delay(500L) }
                    }
                }
            }

        assertEquals(
            HostedExecution.Rejected(
                HostedQueryFailure.BUDGET_EXCEEDED,
                HostedQueryStage.SEMANTIC_READ,
                ExecutionBudgetPresence.Present(admittedReport),
            ),
            result,
        )
        val receipt = receipts.single()
        assertEquals(4_500_000_000L, receipt.durationNanos)
        assertEquals(4_500_000_000L, receipt.stages.single { it.stage == HostedQueryStage.SEMANTIC_READ }.durationNanos)
        assertInstanceOf(HostedSemanticBudgetObservation.Admitted::class.java, receipt.semanticBudget)
        assertEquals(HostedDiagnosticOutcome.Rejected(HostedQueryFailure.BUDGET_EXCEEDED), receipt.outcome)
        assertEquals(HostedExecution.Completed(42), executor.execute(executor.endpoint) { 42 })
        executor.retire()
        executor.drain()
    }

    private fun <Value> Refinement<Value, *>.proven(): Value = (this as Refinement.Refined).value

    private enum class Cancellation(val failure: HostedQueryFailure) {
        CALLER(HostedQueryFailure.CANCELLED),
        OWNER(HostedQueryFailure.RETIRED),
    }
}
