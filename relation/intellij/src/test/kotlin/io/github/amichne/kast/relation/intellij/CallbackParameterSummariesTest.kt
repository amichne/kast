package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.CallbackParameterSummary
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Detached fact admission and accounting, without claiming native compiler resolution. */
class CallbackParameterSummariesTest {
    private val fixture = RelationReadTest()
    private val request = fixture.request(RelationMeaning.Callees)

    @Test
    fun `lookup reuses admitted exact formal without charging duplicate storage`() {
        val summary = summary()
        val summaries =
            CallbackParameterSummaries(
                RelationBudget(
                    request.budget.resources,
                    RelationByteLimit.parse(summary.retainedBytes).refined(),
                )
            )
        assertNull(summaries.find(summary.formal))
        assertTrue(summaries.retain(summary) is Refinement.Refined)
        assertSame(summary, summaries.find(summary.formal))
        assertTrue(summaries.retain(summary) is Refinement.Refined)
        assertEquals(Refinement.Rejected(CallbackInvocationFlowCause.BYTE_LIMIT_REACHED), summaries.retention.admit(1L))
    }

    @Test
    fun `cache storage and scan facts share admission and failed cache entry stays absent`() {
        val summary = summary()
        val summaries =
            CallbackParameterSummaries(
                RelationBudget(
                    request.budget.resources,
                    RelationByteLimit.parse(summary.retainedBytes).refined(),
                )
            )
        assertTrue(summaries.retention.admit(1L) is Refinement.Refined)
        assertEquals(Refinement.Rejected(CallbackInvocationFlowCause.BYTE_LIMIT_REACHED), summaries.retain(summary))
        assertNull(summaries.find(summary.formal))
    }

    @Test
    fun `another exact formal does not alias a cached summary`() {
        val summary = summary()
        val summaries = CallbackParameterSummaries(request.budget)
        assertTrue(summaries.retain(summary) is Refinement.Refined)
        val target = fixture.fact(request, identity = "another").target
        val formal =
            CallbackParameterIdentity.fromCompiler(
                    target,
                    ValueArgumentPosition.parse(0).refined(),
                    RelationOccurrence.fromBoundary(target.file, 72, 73).refined(),
                )
                .refined()
        assertNull(summaries.find(formal))
    }

    private fun summary(): CallbackParameterSummary {
        val target = fixture.fact(request).target
        val formal =
            CallbackParameterIdentity.fromCompiler(
                    target,
                    ValueArgumentPosition.parse(0).refined(),
                    RelationOccurrence.fromBoundary(target.file, 72, 73).refined(),
                )
                .refined()
        return CallbackParameterSummary.fromCompiler(
                formal,
                emptyList(),
                emptySet(),
                scan = CallbackInvocationScan.EXHAUSTIVE,
            )
            .refined()
    }

    private fun <V, F> Refinement<V, F>.refined(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Unexpected rejection: $failure")
        }
}
