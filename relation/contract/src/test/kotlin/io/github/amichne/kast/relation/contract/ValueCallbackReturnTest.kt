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

    @Test
    fun `inner execution requires invocation of its supplying outer body`() {
        for (outerInvoked in listOf(false, true)) {
            val fixture = CallbackInvocationFlowFixture()
            val outer = fixture.anonymous(30, 170)
            val inner = fixture.anonymous(60, 90)
            val outerBinding = suppliedBody(fixture, fixture.supplyingOwner, 20, 180)
            val innerBinding = suppliedBody(fixture, outer, 50, 100)
            val invoked = formalInvocation(fixture)
            val outerFlow =
                CallbackInvocationFlow.fromCompiler(
                        fixture.lease.identity,
                        outer,
                        CallbackBindingEvidence.Bound(outerBinding),
                        if (outerInvoked) listOf(invoked) else emptyList(),
                        emptySet(),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .flowRefined()
            val ownerBinding =
                CallbackBodyBinding.fromCompiler(
                        outer,
                        CallbackBodySupply.Invocation(fixture.occurrence(20, 180)),
                        outerFlow.binding,
                        setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                    )
                    .flowRefined()
                    .withExecution(outerFlow)
                    .flowRefined()
            val flow =
                CallbackInvocationFlow.fromCompiler(
                        fixture.lease.identity,
                        inner,
                        CallbackBindingEvidence.Bound(innerBinding),
                        listOf(invoked),
                        setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .flowRefined()
                    .withOwnerBindings(listOf(ownerBinding))
                    .flowRefined()
            val graph = CompleteCallbackFlowGraph.admit(flow).flowRefined()
            val owner = ValueExecutionOwner.callback(fixture.caller, inner, graph)
            if (outerInvoked) {
                val admitted = owner.flowRefined()
                assertEquals(inner, (admitted.context as ValueExecutionContext.Callback).body)
                assertEquals(2, admitted.context.invocations.size)
            } else assertEquals(Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION), owner)
        }
    }

    @Test
    fun `reachable formal cannot activate an uninvoked anonymous invocation owner`() {
        val fixture = CallbackInvocationFlowFixture()
        val hiddenOwner = uninvokedOwnerBinding(fixture)
        for (invocations in listOf(listOf(fixture.nested), listOf(formalInvocation(fixture), fixture.nested))) {
            val flow =
                CallbackInvocationFlow.fromCompiler(
                        fixture.lease.identity,
                        fixture.body,
                        CallbackBindingEvidence.Bound(fixture.binding),
                        invocations,
                        setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .flowRefined()
                    .withOwnerBindings(listOf(hiddenOwner))
                    .flowRefined()
            val graph = CompleteCallbackFlowGraph.admit(flow).flowRefined()
            assertEquals(
                Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION),
                ValueExecutionOwner.callback(fixture.caller, fixture.body, graph),
            )
        }
    }

    private fun uninvokedOwnerBinding(fixture: CallbackInvocationFlowFixture): CallbackBodyBinding {
        val hidden = fixture.nested.owner as RelationCallableBody.Anonymous
        val ignored = fixture.endpoint("ignore", 410, 500, listOf("()->String"))
        val hiddenBinding =
            CallbackArgumentBinding.fromCompiler(
                    ValueInvocation.fromCompiler(fixture.target, fixture.range(250, 310), ignored).flowRefined(),
                    RelationCallableBody.Named.fromCompiler(fixture.target.evidence).flowRefined(),
                    ValueArgumentPosition.parse(0).flowRefined(),
                    fixture.occurrence(425, 445),
                )
                .flowRefined()
        val hiddenFlow =
            CallbackInvocationFlow.fromCompiler(
                    fixture.lease.identity,
                    hidden,
                    CallbackBindingEvidence.Bound(hiddenBinding),
                    emptyList(),
                    emptySet(),
                    CallbackInvocationScan.EXHAUSTIVE,
                )
                .flowRefined()
        return CallbackBodyBinding.fromCompiler(
                hidden,
                CallbackBodySupply.Invocation(fixture.occurrence(250, 310)),
                hiddenFlow.binding,
                setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
            )
            .flowRefined()
            .withExecution(hiddenFlow)
            .flowRefined()
    }

    private fun suppliedBody(
        fixture: CallbackInvocationFlowFixture,
        owner: RelationCallableBody,
        start: Int,
        end: Int,
    ): CallbackArgumentBinding =
        CallbackArgumentBinding.fromCompiler(
                ValueInvocation.fromCompiler(fixture.caller, fixture.range(start, end), fixture.target).flowRefined(),
                owner,
                fixture.binding.position,
                fixture.binding.parameter,
            )
            .flowRefined()

    private fun formalInvocation(fixture: CallbackInvocationFlowFixture): CallbackParameterInvocation =
        CallbackParameterInvocation.fromCompiler(
                fixture.occurrence(320, 330),
                RelationCallableBody.Named.fromCompiler(fixture.target.evidence).flowRefined(),
            )
            .flowRefined()

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
        val invoked = formalInvocation(fixture)
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
