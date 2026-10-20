package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure
import io.github.amichne.kast.relation.contract.ValueFlowObligationEvidence
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class NativeValueCallbackContractRejectedTest {
    private val fixture = NativeCallbackFailureCase()

    @Test
    fun `invalid forwarding path keeps its raw contract failure as an obligation`() {
        val source = fixture.argument()
        val read = nativeValueCallbackContractRejected(source, CallbackInvocationFlowFailure.INVALID_FORWARDING_PATH)
        assertInstanceOf(Refinement.Refined::class.java, read)
        val obligation = read.failureValue()
        assertEquals(source, obligation.site)
        assertEquals(ValueFlowUnsupportedCause.CALLBACK_FLOW_UNPROVEN, obligation.cause)
        val evidence =
            assertInstanceOf(ValueFlowObligationEvidence.CallbackContractRejected::class.java, obligation.evidence)
        assertEquals(CallbackInvocationFlowFailure.INVALID_FORWARDING_PATH, evidence.cause)
    }

    @Test
    fun `unrelated expression rejects callback contract failure evidence`() {
        assertEquals(
            Refinement.Rejected(ValueFlowRejection.STALE_SITE),
            nativeValueCallbackContractRejected(
                fixture.expression(),
                CallbackInvocationFlowFailure.INVALID_FORWARDING_PATH,
            ),
        )
    }
}
