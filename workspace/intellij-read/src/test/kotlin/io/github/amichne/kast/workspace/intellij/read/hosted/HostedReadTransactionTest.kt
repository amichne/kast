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
        val result = runHostedReadTransaction(progress, { Refinement.Refined(Unit) }) { allowance ->
            now += allowance.semantic.value * 1_000_000L
            42
        }
        assertEquals(HostedSemanticRead.Rejected(HostedQueryFailure.BUDGET_EXCEEDED), result)
        assertEquals(HostedQueryStage.CONTENT_REVALIDATION, progress.stage)
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
