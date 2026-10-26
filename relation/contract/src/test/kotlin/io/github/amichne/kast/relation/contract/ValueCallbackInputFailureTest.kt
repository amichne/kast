package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ValueCallbackInputFailureTest {
    @Test
    fun `callback contract and graph reasons remain distinct evidence with canonical retention`() {
        val site =
            ValueSite.fromCompiler(flowEndpoint("owner", 0, 200), flowRange(10, 20), ValueRole.ExpressionResult)
                .flowRefined()
        val failures =
            CallbackInvocationFlowCause.entries.map(ValueCallbackInputFailure::Callback) +
                CallbackInvocationFlowFailure.entries.map(ValueCallbackInputFailure::Contract) +
                listOf(
                    ValueCallbackInputFailure.Graph(StaticCallbackGraphFailure.IncompleteScan),
                    ValueCallbackInputFailure.Graph(
                        StaticCallbackGraphFailure.Unavailable(CallbackInvocationFlowCause.NO_INVOCATION_PROVEN)
                    ),
                )
        val obligations = failures.map { ValueFlowObligation.fromCallbackInput(site, it).flowRefined() }
        assertEquals(failures.size, obligations.distinct().size)
        assertEquals(failures.size, failures.map { it.canonicalProjection() }.distinct().size)
        for ((failure, obligation) in failures.zip(obligations)) {
            assertSame(failure, (obligation.evidence as ValueFlowObligationEvidence.CallbackInput).failure)
            assertEquals(ValueFlowUnsupportedCause.NESTED_EXECUTION, obligation.cause)
            assertTrue(valueObligationEvidenceStorageBytes(obligation.evidence) > 0)
        }
        val budget =
            ValueFlowObligation.fromCallbackInput(
                    site,
                    ValueCallbackInputFailure.Callback(CallbackInvocationFlowCause.WORK_LIMIT_REACHED),
                    ValueFlowUnsupportedCause.WORK_LIMIT_REACHED,
                )
                .flowRefined()
        assertEquals(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED, budget.cause)
        assertNotEquals(budget.evidence, ValueFlowObligationEvidence.None)
    }

    @Test
    fun `unresolved graph payload keeps its basis and cannot attach to another epoch`() {
        val fixture = CallbackInvocationFlowFixture()
        val flow = fixture.observed(emptyList(), setOf(CallbackInvocationFlowCause.NO_INVOCATION_PROVEN))
        val failure = ValueCallbackInputFailure.Graph(StaticCallbackGraphFailure.Unresolved(flow))
        val local =
            ValueSite.fromCompiler(fixture.caller, fixture.range(10, 20), ValueRole.ExpressionResult).flowRefined()
        assertSame(
            failure,
            (ValueFlowObligation.fromCallbackInput(local, failure).flowRefined().evidence
                    as ValueFlowObligationEvidence.CallbackInput)
                .failure,
        )
        assertTrue(failure.retainedBytes > flow.retainedBytes)
        val foreign =
            ValueSite.fromCompiler(
                    flowEndpoint("owner", 0, 200, generation = 2),
                    flowRange(10, 20),
                    ValueRole.ExpressionResult,
                )
                .flowRefined()
        assertEquals(
            Refinement.Rejected(ValueFlowObligationFailure.BASIS_MISMATCH),
            ValueFlowObligation.fromCallbackInput(foreign, failure),
        )
    }
}
