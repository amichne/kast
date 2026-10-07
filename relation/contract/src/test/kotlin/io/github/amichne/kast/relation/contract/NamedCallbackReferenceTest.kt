package io.github.amichne.kast.relation.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class NamedCallbackReferenceTest {
    private val fixture = CallbackInvocationFlowFixture()

    @Test
    fun `supplying a reference retains its target without manufacturing invocation`() =
        with(fixture) {
            val formal = CallbackParameterIdentity.fromCompiler(target, binding.position, binding.parameter).value()
            val summary =
                CallbackParameterSummary.fromCompiler(
                        formal,
                        emptyList(),
                        emptySet(),
                        scan = CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            val proof =
                NamedCallbackReference.fromCompiler(
                        occurrence(30, 40),
                        caller,
                        CallbackReferenceReceivers(CallbackReferenceReceiver.Absent, CallbackReferenceReceiver.Absent),
                        NamedCallbackReferenceFlow.Supplied(binding, summary),
                    )
                    .value()
            assertEquals(caller, proof.target)
            assertFalse(proof.hasStaticInvocation)
        }

    @Test
    fun `reference binding rejects a supplier call that does not contain its reference`() =
        with(fixture) {
            val formal = CallbackParameterIdentity.fromCompiler(target, binding.position, binding.parameter).value()
            val summary =
                CallbackParameterSummary.fromCompiler(
                        formal,
                        emptyList(),
                        emptySet(),
                        scan = CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .value()
            assertEquals(
                NamedCallbackReferenceFailure.REFERENCE_OUTSIDE_SUPPLY,
                NamedCallbackReference.fromCompiler(
                        occurrence(100, 120),
                        caller,
                        CallbackReferenceReceivers(CallbackReferenceReceiver.Absent, CallbackReferenceReceiver.Absent),
                        NamedCallbackReferenceFlow.Supplied(binding, summary),
                    )
                    .failure(),
            )
        }

    @Test
    fun `unavailable reference snapshots every obligation without dropping additional failures`() {
        val input =
            mutableSetOf(CallbackInvocationFlowCause.PARAMETER_ESCAPES, CallbackInvocationFlowCause.TIME_LIMIT_REACHED)
        val unavailable = NamedCallbackReferenceFlow.Unavailable(CallbackInvocationFlowCause.EXTERNAL_CALLABLE, input)
        input.clear()
        assertEquals(
            setOf(
                CallbackInvocationFlowCause.EXTERNAL_CALLABLE,
                CallbackInvocationFlowCause.PARAMETER_ESCAPES,
                CallbackInvocationFlowCause.TIME_LIMIT_REACHED,
            ),
            unavailable.causes,
        )
    }
}
