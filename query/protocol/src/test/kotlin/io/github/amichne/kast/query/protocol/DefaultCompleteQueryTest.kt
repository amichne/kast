package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryCompletionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionUnprovenReason
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryStaticModelDocument
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

internal class DefaultCompleteQueryTest : AutomaticSymbolQueryCase() {
    private val defaultRequest
        get() = QueryRunRequest.Run(request.from, request.steps, request.output, request.execution)

    @Test
    fun `default drains every issued page and preserves strict original question`() = runTest {
        val script = Script(listOf(listOf(row), emptyList(), listOf(row)))
        val result =
            CanonicalQueryProtocol(script.operations, fixture.references)
                .executeAutomatically(defaultRequest, fixture.authority, budget, policy(1)) as OperationOutcome.Complete
        assertEquals(2, result.evidence.payload.invocation!!.accumulatedRowCount)
        assertEquals(
            QueryCompletionPolicyDocument.CompleteOnly(QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1),
            result.evidence.payload.question.completion,
        )
        script.assertDrained()
    }

    @Test
    fun `default rejects terminal incompleteness instead of publishing qualified success`() = runTest {
        val script = Script(listOf(listOf(row)), terminal = true)
        val result =
            CanonicalQueryProtocol(script.operations, fixture.references)
                .executeAutomatically(defaultRequest, fixture.authority, budget, policy()) as OperationOutcome.Rejected
        val failure = assertInstanceOf(QueryRunRejection.CompletionUnproven::class.java, result.reason)
        assertEquals(QueryCompletionUnprovenReason.INCOMPLETE_EXECUTION, failure.reason)
        script.assertDrained()
    }

    @Test
    fun `explicit progressive preserves useful qualified execution`() = runTest {
        val script = Script(listOf(listOf(row)), terminal = true)
        val result =
            CanonicalQueryProtocol(script.operations, fixture.references)
                .executeAutomatically(request, fixture.authority, budget, policy())
        assertInstanceOf(OperationOutcome.Qualified::class.java, result)
        script.assertDrained()
    }
}
