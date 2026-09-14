@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
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
    fun `equal configured deadlines shrink semantic time after capture and retain qualified publication`() = runTest {
        lateinit var admittedReport: ExecutionBudgetReport
        val limits =
            (io.github.amichne.kast.kernel.ReadLimits.resolve(mapOf("KAST_READ_HOST_QUERY_MILLIS" to "2000"))
                    as io.github.amichne.kast.kernel.Refinement.Refined)
                .value
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val executor =
            HostedQueryExecutor(backgroundScope, { testScheduler.currentTime * 1_000_000 }) { policy ->
                HostedReadDiagnostics({ testScheduler.currentTime * 1_000_000 }, policy, receipts::add)
            }
        val result =
            executor.execute(
                executor.endpoint,
                limits,
                outcome = { HostedDiagnosticOutcome.Evaluated(HostedEvaluationOutcome.QUALIFIED) },
            ) { progress ->
                progress.advance(HostedQueryStage.MODEL_CAPTURE)
                delay(318)
                runHostedReadTransaction(progress, { io.github.amichne.kast.kernel.Refinement.Refined(Unit) }) {
                    allowance ->
                    admittedReport = ExecutionBudgetReport.from(allowance.executionBudget)
                    assertEquals(1432, allowance.semantic.value)
                    delay(allowance.semantic.value + 50)
                    42
                }
            }
        assertEquals(
            HostedExecution.Completed(
                HostedSemanticRead.Resolved(42),
                HostedQueryStage.RESULT_DETACHED,
                ExecutionBudgetPresence.Present(admittedReport),
            ),
            result,
        )
        assertEquals(HostedDiagnosticOutcome.Evaluated(HostedEvaluationOutcome.QUALIFIED), receipts.single().outcome)
        assertEquals(HostedSemanticBudgetObservation.Admitted(1682, 250, 1432, 1432), receipts.single().semanticBudget)
        val encoded =
            kotlinx.serialization.json.Json.parseToJsonElement(receipts.single().encode())
                as kotlinx.serialization.json.JsonObject
        val budget = encoded.getValue("semanticBudget") as kotlinx.serialization.json.JsonObject
        assertEquals(
            setOf("type", "remainingHostMillis", "completionReserveMillis", "semanticMillis", "diagnosticScopeMillis"),
            budget.keys,
        )
        assertEquals(kotlinx.serialization.json.JsonPrimitive("admitted"), budget.getValue("type"))
        assertEquals(kotlinx.serialization.json.JsonPrimitive(1432), budget.getValue("semanticMillis"))
        executor.retire()
        executor.drain()
    }

    @Test
    fun `capture exhausting the semantic allowance rejects before evaluation and records admission failure`() =
        runTest {
            val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
            val executor =
                HostedQueryExecutor(backgroundScope, { testScheduler.currentTime * 1_000_000 }) { policy ->
                    HostedReadDiagnostics({ testScheduler.currentTime * 1_000_000 }, policy, receipts::add)
                }
            val result =
                executor.execute(executor.endpoint) { progress ->
                    delay(3800)
                    runHostedReadTransaction(progress, { io.github.amichne.kast.kernel.Refinement.Refined(Unit) }) {
                        error("Exhausted allowance must never invoke the evaluator")
                    }
                }
            assertEquals(
                HostedExecution.Completed(
                    HostedSemanticRead.Rejected(HostedQueryFailure.BUDGET_EXCEEDED),
                    HostedQueryStage.SEMANTIC_READ,
                ),
                result,
            )
            assertEquals(
                HostedDiagnosticOutcome.Rejected(HostedQueryFailure.BUDGET_EXCEEDED),
                receipts.single().outcome,
            )
            assertEquals(HostedSemanticBudgetObservation.Exhausted(200, 250), receipts.single().semanticBudget)
            val encoded =
                kotlinx.serialization.json.Json.parseToJsonElement(receipts.single().encode())
                    as kotlinx.serialization.json.JsonObject
            val budget = encoded.getValue("semanticBudget") as kotlinx.serialization.json.JsonObject
            assertEquals(setOf("type", "remainingHostMillis", "completionReserveMillis"), budget.keys)
            assertEquals(kotlinx.serialization.json.JsonPrimitive("exhausted"), budget.getValue("type"))
            executor.retire()
            executor.drain()
        }

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
