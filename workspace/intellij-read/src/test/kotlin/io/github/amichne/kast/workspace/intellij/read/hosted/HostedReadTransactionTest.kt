package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HostedReadTransactionTest {
    @Test
    fun `caller elapsed limit rejects detached evaluation before publication`() = runTest {
        var now = 0L
        val progress = HostedQueryProgress(clock = { now }, completion = HostedReadCompletionPolicy.CALLER_ELAPSED)
        val result =
            runHostedReadTransaction(progress, { Refinement.Refined(Unit) }) { allowance ->
                now += allowance.semantic.value * 1_000_000L
                42
            }
        assertEquals(HostedSemanticRead.Rejected(HostedQueryFailure.BUDGET_EXCEEDED), result)
        assertEquals(HostedQueryStage.CONTENT_REVALIDATION, progress.stage)
    }

    @Test
    fun `final freshness consumes caller grant and preserves admitted report`() = runTest {
        var now = 0L
        var validations = 0
        val progress = HostedQueryProgress(clock = { now }, completion = HostedReadCompletionPolicy.CALLER_ELAPSED)
        val result =
            runHostedReadTransaction(
                progress,
                {
                    if (++validations == 2) now += 2_000_000_000L
                    Refinement.Refined(Unit)
                },
            ) {
                42
            }
        assertEquals(HostedSemanticRead.Rejected(HostedQueryFailure.BUDGET_EXCEEDED), result)
        assertTrue(progress.executionBudget is io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence.Present)
    }

    @Test
    fun `one nanosecond before caller boundary publishes and containment retains its prior policy`() = runTest {
        for (policy in HostedReadCompletionPolicy.entries) {
            var now = 0L
            val progress = HostedQueryProgress(clock = { now }, completion = policy)
            val result =
                runHostedReadTransaction(progress, { Refinement.Refined(Unit) }) { allowance ->
                    now +=
                        allowance.semantic.value * 1_000_000L +
                            if (policy == HostedReadCompletionPolicy.CALLER_ELAPSED) -1L else 1L
                    42
                }
            assertEquals(HostedSemanticRead.Resolved(42), result)
        }
    }

    @Test
    fun `caller completion policy does not convert cancellation into timed publication`() = runTest {
        val progress = HostedQueryProgress(completion = HostedReadCompletionPolicy.CALLER_ELAPSED)
        val failure =
            assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                kotlinx.coroutines.test.runTest {
                    runHostedReadTransaction(progress, { Refinement.Refined(Unit) }) {
                        throw kotlinx.coroutines.CancellationException("fixture cancellation")
                    }
                }
            }
        assertEquals("fixture cancellation", failure.message)
        assertEquals(HostedQueryStage.SEMANTIC_READ, progress.stage)
    }

    @Test
    fun `regressed clock rejects caller completion while containment policy is unchanged`() = runTest {
        for (policy in HostedReadCompletionPolicy.entries) {
            var now = 10L
            val progress = HostedQueryProgress(clock = { now }, completion = policy)
            val result =
                runHostedReadTransaction(progress, { Refinement.Refined(Unit) }) {
                    now = 9L
                    42
                }
            val expected =
                when (policy) {
                    HostedReadCompletionPolicy.CALLER_ELAPSED ->
                        HostedSemanticRead.Rejected(HostedQueryFailure.BUDGET_EXCEEDED)
                    HostedReadCompletionPolicy.HOST_CONTAINMENT -> HostedSemanticRead.Resolved(42)
                }
            assertEquals(expected, result)
        }
    }

    @Test
    fun `failed admission never evaluates a plan`() = runTest {
        var evaluated = false
        val result =
            runHostedReadTransaction(
                HostedQueryProgress(),
                validate = { Refinement.Rejected(HostedQueryFailure.CONTENT_MOVED) },
            ) {
                evaluated = true
                42
            }
        assertFalse(evaluated)
        assertEquals(HostedSemanticRead.Rejected(HostedQueryFailure.CONTENT_MOVED), result)
    }

    @Test
    fun `changed epoch discards a fully evaluated result`() = runTest {
        var current = true
        val progress = HostedQueryProgress()
        val result =
            runHostedReadTransaction(
                progress,
                validate = {
                    if (current) Refinement.Refined(Unit) else Refinement.Rejected(HostedQueryFailure.CONTENT_MOVED)
                },
            ) {
                current = false
                42
            }
        assertEquals(HostedSemanticRead.Rejected(HostedQueryFailure.CONTENT_MOVED), result)
        assertEquals(HostedQueryStage.CONTENT_REVALIDATION, progress.stage)
    }

    @Test
    fun `a complete evaluation is released only after the final freshness check`() = runTest {
        val events = mutableListOf<String>()
        val progress = HostedQueryProgress()
        val result =
            runHostedReadTransaction(
                progress,
                validate = {
                    events += "validate"
                    Refinement.Refined(Unit)
                },
            ) {
                events += "evaluate"
                42
            }
        assertEquals(listOf("validate", "evaluate", "validate"), events)
        assertEquals(HostedSemanticRead.Resolved(42), result)
        assertEquals(HostedQueryStage.RESULT_DETACHED, progress.stage)
    }
}
