package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ValueCallbackContractRefusalTest {
    @Test
    fun `detached callback rejection preserves its raw failure without a freshness claim`() {
        val source = ValueCallbackOracleCase().argument()
        val cause = CallbackInvocationFlowFailure.INVALID_FORWARDING_PATH
        val obligation = ValueFlowObligation.fromCallbackContractRejected(source, cause).flowRefined()
        val evidence =
            assertInstanceOf(ValueFlowObligationEvidence.CallbackContractRejected::class.java, obligation.evidence)
        assertSame(source, obligation.site)
        assertEquals(cause, evidence.cause)
        assertEquals(ValueFlowUnsupportedCause.CALLBACK_FLOW_UNPROVEN, obligation.cause)
    }

    @Test
    fun `proof limit rejection keeps its exact raw and outer cause`() {
        val source = ValueCallbackOracleCase().argument()
        val cause = CallbackInvocationFlowFailure.PROOF_LIMIT_REACHED
        val obligation = ValueFlowObligation.fromCallbackContractRejected(source, cause).flowRefined()
        val evidence =
            assertInstanceOf(ValueFlowObligationEvidence.CallbackContractRejected::class.java, obligation.evidence)
        assertEquals(cause, evidence.cause)
        assertEquals(ValueFlowUnsupportedCause.CALLBACK_PROOF_LIMIT_REACHED, obligation.cause)
    }

    @Test
    fun `an unrelated expression cannot issue a callback contract refusal`() {
        val source = ValueCallbackOracleCase().invocation.resultSite()
        assertEquals(
            Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH),
            ValueFlowObligation.fromCallbackContractRejected(source, CallbackInvocationFlowFailure.INVALID_SCAN_PROOF),
        )
    }
}
