package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.ValueFlowObligationEvidence
import io.github.amichne.kast.relation.contract.ValueFlowObligationFailure
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/** Detached native failure projection preserves the raw compiler cause and rejects an unrelated role. */
class NativeValueCallbackUnavailableTest {
    private val fixture = NativeCallbackFailureCase()

    @Test
    fun `parameter escape keeps its raw cause beside the unresolved classification`() {
        val source = fixture.argument()
        val obligation =
            nativeValueCallbackUnavailable(source, CallbackInvocationFlowCause.PARAMETER_ESCAPES).failureValue()
        assertEquals(source, obligation.site)
        assertEquals(ValueFlowUnsupportedCause.CALLBACK_FLOW_UNPROVEN, obligation.cause)
        val evidence =
            assertInstanceOf(ValueFlowObligationEvidence.CallbackUnavailable::class.java, obligation.evidence)
        assertEquals(CallbackInvocationFlowCause.PARAMETER_ESCAPES, evidence.cause)
    }

    @Test
    fun `unrelated expression cannot carry unavailable callback evidence`() {
        assertEquals(
            Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH),
            nativeValueCallbackUnavailable(
                fixture.expression(),
                CallbackInvocationFlowCause.PARAMETER_ESCAPES,
            ),
        )
    }
}
