@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.workspace.contract.WorkspaceNativeReadSettlement
import io.github.amichne.kast.workspace.contract.WorkspaceReadOperationIdentity
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
            assertEquals(HostedExecution.Rejected(HostedQueryFailure.RETIRED), executor.execute(executor.endpoint) { 7 })
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

    @Test
    fun `two readers overlap at configured capacity and cancellation owns only one slot`() = runTest {
        val limits =
            (io.github.amichne.kast.kernel.ReadLimits.resolve(mapOf("KAST_READ_HOST_READERS" to "2"))
                    as io.github.amichne.kast.kernel.Refinement.Refined)
                .value
        val executor = HostedQueryExecutor(backgroundScope)
        val firstEntered = CompletableDeferred<HostedQueryProgress>()
        val secondEntered = CompletableDeferred<HostedQueryProgress>()
        val firstCleanup = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        val first = async {
            executor.execute(executor.endpoint, limits) { progress ->
                firstEntered.complete(progress)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { firstCleanup.await() }
                }
            }
        }
        val second = async {
            executor.execute(executor.endpoint, limits) { progress ->
                secondEntered.complete(progress)
                secondRelease.await()
                42
            }
        }
        try {
            runCurrent()
            assertTrue(firstEntered.isCompleted)
            assertTrue(secondEntered.isCompleted)
            assertNotSame(firstEntered.await(), secondEntered.await())
            assertCapacityOccupied(executor, limits)
            first.cancel()
            runCurrent()
            assertFalse(first.isCompleted)
            assertCapacityOccupied(executor, limits)
            firstCleanup.complete(Unit)
            first.join()
            assertFalse(second.isCompleted)
            assertEquals(HostedExecution.Completed(7), executor.execute(executor.endpoint, limits) { 7 })
            secondRelease.complete(Unit)
            assertEquals(HostedExecution.Completed(42), second.await())
        } finally {
            firstCleanup.complete(Unit)
            secondRelease.complete(Unit)
            executor.retire()
            executor.drain()
        }
    }

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
                HostedReadDiagnostics({ testScheduler.currentTime * 1_000_000 }, policy, publish = receipts::add)
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
                    HostedReadDiagnostics({ testScheduler.currentTime * 1_000_000 }, policy, publish = receipts::add)
                }
            val result =
                executor.execute(executor.endpoint, shortHostLimits()) { progress ->
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
    fun `deadline drains only its own work while another reader completes`() = runTest {
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
        try {
            assertEquals(HostedExecution.Completed(1), executor.execute(executor.endpoint) { 1 })
        } finally {
            cleanup.complete(Unit)
        }
        runCurrent()
        assertEquals(HostedExecution.Rejected(HostedQueryFailure.BUDGET_EXCEEDED), first.await())
        assertEquals(HostedExecution.Completed(2), executor.execute(executor.endpoint) { 2 })
        executor.retire()
        executor.drain()
    }

    @Test
    fun `retirement drains every admitted read and rejects their late publication`() = runTest {
        val executor = HostedQueryExecutor(backgroundScope)
        val cleanup = List(2) { CompletableDeferred<Unit>() }
        val work = cleanup.map { gate ->
            async {
                executor.execute(executor.endpoint) { progress ->
                    progress.advance(HostedQueryStage.CONTENT_REVALIDATION)
                    withContext(NonCancellable) { gate.await() }
                    42
                }
            }
        }
        try {
            runCurrent()
            executor.retire()
            val retired = async { executor.drain() }
            runCurrent()
            assertFalse(retired.isCompleted)
            cleanup.first().complete(Unit)
            runCurrent()
            assertFalse(retired.isCompleted, "The second invocation still owns its computation")
            cleanup.last().complete(Unit)
            work.forEach {
                assertEquals(
                    HostedExecution.Rejected(HostedQueryFailure.RETIRED, HostedQueryStage.CONTENT_REVALIDATION),
                    it.await(),
                )
            }
            retired.await()
            assertEquals(
                HostedExecution.Rejected(HostedQueryFailure.RETIRED),
                executor.execute(executor.endpoint) { 1 },
            )
        } finally {
            cleanup.forEach { it.complete(Unit) }
            executor.retire()
            executor.drain()
        }
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
        try {
            assertFalse(first.isCompleted)
            assertEquals(HostedExecution.Completed(2), executor.execute(executor.endpoint) { 2 })
            assertFalse(first.isCompleted)
        } finally {
            cleanup.complete(Unit)
        }
        runCurrent()
        first.join()
        assertEquals(HostedExecution.Completed(3), executor.execute(executor.endpoint) { 3 })
        executor.retire()
        executor.drain()
    }

    private suspend fun assertCapacityOccupied(
        executor: HostedQueryExecutor,
        limits: io.github.amichne.kast.kernel.ReadLimits,
    ) {
        assertEquals(
            HostedExecution.Rejected(HostedQueryFailure.BUSY),
            executor.execute(executor.endpoint, limits) { error("Capacity must remain occupied") },
        )
    }
}
