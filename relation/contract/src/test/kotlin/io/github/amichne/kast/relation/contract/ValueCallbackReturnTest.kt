package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/** Explicit compiler facts exercise result matching, not native resolution or runtime execution. */
class ValueCallbackReturnTest {
    @Test
    fun `callback result enters only its exact formal invocation and supplier frame`() {
        val first = callbackResult(20)
        val second = callbackResult(100)
        assertEquals(first.target.enclosing.valueIdentity, second.target.enclosing.valueIdentity)
        assertNotEquals(first.source.executionOwner, second.source.executionOwner)
        val transfer =
            ValueTransfer.fromCompiler(
                    first.source,
                    first.target,
                    ValueTransferKind.SOURCE_CALL_RETURN,
                    first.evidence,
                )
                .flowRefined()
        assertEquals(first.source.valuePath, transfer.target.valuePath)
        assertEquals(1, transfer.target.callStack.frames.size)
        assertEquals(first.frame, transfer.target.callStack.frames.single())
        val wrongOccurrence =
            ValueSite.fromCompiler(
                    first.target.enclosing,
                    flowRange(345, 355),
                    first.target.role,
                    valuePath = first.target.valuePath,
                    callStack = first.target.callStack,
                )
                .flowRefined()
        for (target in
            listOf(
                second.target,
                wrongOccurrence,
                first.target.withState(valuePath = ValueAccessPath.Whole).flowRefined(),
            )) {
            assertEquals(
                Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
                ValueTransfer.fromCompiler(first.source, target, ValueTransferKind.SOURCE_CALL_RETURN, first.evidence),
            )
        }
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(first.source, first.target, ValueTransferKind.SOURCE_CALL_RETURN),
        )
    }

    private fun callbackResult(start: Int): CallbackResult {
        val fixture = CallbackInvocationFlowFixture()
        val body = fixture.anonymous(start + 10, start + 40)
        val call =
            ValueInvocation.fromCompiler(fixture.caller, fixture.range(start, start + 60), fixture.target).flowRefined()
        val binding =
            CallbackArgumentBinding.fromCompiler(
                    call,
                    fixture.supplyingOwner,
                    fixture.binding.position,
                    fixture.binding.parameter,
                )
                .flowRefined()
        val invoked =
            CallbackParameterInvocation.fromCompiler(
                    fixture.occurrence(320, 330),
                    RelationCallableBody.Named.fromCompiler(fixture.target.evidence).flowRefined(),
                )
                .flowRefined()
        val owner = callbackOwner(fixture, body, binding, invoked)
        val invocation = (owner.context as ValueExecutionContext.Callback).invocations.single()
        val frame = ValueCallFrame.direct(call, ValueExecutionOwner.declaration(fixture.target)).flowRefined()
        val path = flowPath(ValuePathSegment.Field(flowField("Result.value", 500, 520)))
        val source =
            ValueSite.fromCompiler(
                    fixture.caller,
                    fixture.range(start + 15, start + 20),
                    ValueRole.Return,
                    owner,
                    path,
                )
                .flowRefined()
        val target =
            ValueSite.fromCompiler(
                    fixture.target,
                    invoked.occurrence.range,
                    ValueRole.ExpressionResult,
                    valuePath = path,
                    callStack = source.callStack.enter(frame).flowRefined(),
                )
                .flowRefined()
        return CallbackResult(
            source,
            target,
            frame,
            ValueSourceTransfer.fromCompiler(ValueSourceOperation.CallbackReturn(invocation, frame)),
        )
    }

    private fun callbackOwner(
        fixture: CallbackInvocationFlowFixture,
        body: RelationCallableBody.Anonymous,
        binding: CallbackArgumentBinding,
        invoked: CallbackParameterInvocation,
    ): ValueExecutionOwner {
        val flow =
            CallbackInvocationFlow.fromCompiler(
                    fixture.lease.identity,
                    body,
                    CallbackBindingEvidence.Bound(binding),
                    listOf(invoked),
                    emptySet(),
                    CallbackInvocationScan.EXHAUSTIVE,
                )
                .flowRefined()
        return ValueExecutionOwner.callback(fixture.caller, body, CompleteCallbackFlowGraph.admit(flow).flowRefined())
            .flowRefined()
    }

    private data class CallbackResult(
        val source: ValueSite,
        val target: ValueSite,
        val frame: ValueCallFrame,
        val evidence: ValueSourceTransfer,
    )
}
