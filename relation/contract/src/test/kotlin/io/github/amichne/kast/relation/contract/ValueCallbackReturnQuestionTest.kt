package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ValueCallbackReturnQuestionTest {
    @Test
    fun `a fresh qualified zero answer preserves the same callback question`() {
        val f = ValueCallbackOracleCase()
        val fresh = qualified(f)
        val admitted = ValueFlowObligation.fromCallback(returned(f), fresh)
        assertInstanceOf(Refinement.Refined::class.java, admitted)
        val obligation = (admitted as Refinement.Refined).value
        val proof = assertInstanceOf(ValueFlowObligationEvidence.CallbackFlow::class.java, obligation.evidence)
        assertSame(fresh, proof.flow)
        assertEquals(emptyList<CallbackParameterInvocation>(), proof.flow.invocations)
        assertEquals(setOf(CallbackInvocationFlowCause.WORK_LIMIT_REACHED), proof.flow.obligations)
        assertEquals(CallbackInvocationScan.INCOMPLETE, proof.flow.scan)
    }

    @Test
    fun `a diagnostic return rejects another body supplier or basis`() {
        val f = ValueCallbackOracleCase()
        val source = returned(f)
        val otherBody = (callbackOracleOwner(f.host, 45, 65).body as ValueExecutionBody.Anonymous).callable
        assertEquals(
            Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH),
            ValueFlowObligation.fromCallback(source, qualified(f, body = otherBody)),
        )
        assertEquals(
            Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH),
            ValueFlowObligation.fromCallback(source, qualified(ValueCallbackOracleCase(supplyStart = 10))),
        )
        assertEquals(
            Refinement.Rejected(ValueFlowObligationFailure.BASIS_MISMATCH),
            ValueFlowObligation.fromCallback(source, qualified(ValueCallbackOracleCase(generation = 2))),
        )
    }

    @Test
    fun `a diagnostic return keeps the exact active callback callee`() {
        val f = ValueCallbackOracleCase()
        assertEquals(
            Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH),
            ValueFlowObligation.fromCallback(returned(f, ValueExecutionOwner.declaration(f.host)), f.flow),
        )
    }

    @Test
    fun `an unavailable callback return cannot substitute its execution owner`() {
        val f = ValueCallbackOracleCase()
        assertEquals(
            Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH),
            ValueFlowObligation.fromCallbackUnavailable(
                returned(f, ValueExecutionOwner.declaration(f.host)),
                CallbackInvocationFlowCause.PARAMETER_ESCAPES,
            ),
        )
    }

    @Test
    fun `a rejected callback return cannot substitute its execution owner`() {
        val f = ValueCallbackOracleCase()
        assertEquals(
            Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH),
            ValueFlowObligation.fromCallbackContractRejected(
                returned(f, ValueExecutionOwner.declaration(f.host)),
                CallbackInvocationFlowFailure.INVALID_FORWARDING_PATH,
            ),
        )
    }

    @Test
    fun `the same supplying occurrence cannot substitute another invocation execution`() {
        val f = ValueCallbackOracleCase()
        val binding = (f.flow.binding as CallbackBindingEvidence.Bound).binding
        val invocation =
            ValueInvocation.fromCompiler(
                    binding.invocation.enclosing,
                    binding.invocation.range,
                    binding.invocation.callable,
                    callbackOracleOwner(f.host, 10, 150),
                )
                .flowRefined()
        val other =
            CallbackArgumentBinding.fromCompiler(
                    invocation,
                    binding.invocationOwner,
                    binding.position,
                    binding.parameter,
                )
                .flowRefined()
        val fresh = qualified(f, binding = CallbackBindingEvidence.Bound(other))
        assertEquals(
            Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH),
            ValueFlowObligation.fromCallback(returned(f), fresh),
        )
    }

    @Test
    fun `a positive return still needs the full callback answer in its active frame`() {
        val f = ValueCallbackOracleCase()
        val source = returned(f)
        val result = f.invocation.resultSite().withState(callStack = f.argument().callStack).flowRefined()
        fun transfer(frame: ValueCallFrame) =
            ValueTransfer.fromCompiler(
                source,
                result,
                ValueTransferKind.SOURCE_CALL_RETURN,
                ValueSourceTransfer.fromCompiler(ValueSourceOperation.Return(frame)),
            )
        assertInstanceOf(Refinement.Refined::class.java, transfer(f.frame()))
        val freshFrame =
            ValueCallFrame.callback(
                    f.invocation,
                    f.callback,
                    qualified(f, invocations = listOf(f.selected)),
                    f.selected,
                    f.supplyingFrame,
                )
                .flowRefined()
        assertEquals(Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH), transfer(freshFrame))
    }

    private fun returned(f: ValueCallbackOracleCase, owner: ValueExecutionOwner = f.callback) =
        ValueSite.fromCompiler(
                f.host,
                flowRange(50, 55),
                ValueRole.Return,
                owner,
                callStack = f.entered().callStack,
            )
            .flowRefined()

    private fun qualified(
        f: ValueCallbackOracleCase,
        body: RelationCallableBody.Anonymous = f.flow.body,
        invocations: List<CallbackParameterInvocation> = emptyList(),
        binding: CallbackBindingEvidence = f.flow.binding,
    ) =
        CallbackInvocationFlow.fromCompiler(
                f.flow.basis,
                body,
                binding,
                invocations,
                setOf(CallbackInvocationFlowCause.WORK_LIMIT_REACHED),
                CallbackInvocationScan.INCOMPLETE,
            )
            .flowRefined()
}
