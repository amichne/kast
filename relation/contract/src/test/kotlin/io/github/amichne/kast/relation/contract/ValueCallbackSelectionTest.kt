package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ValueCallbackSelectionTest {
    @Test
    fun `repeated invocations retain distinct execution identity and complete graph`() {
        val selected = selectedReturns()
        val (owners, calls) = selected
        val sources = selected.sources
        assertNotEquals(owners[0].identity, owners[1].identity)
        assertNotEquals(sources[0].identity, sources[1].identity)
        assertNotEquals(buildString { valueState(sources[0]) }, buildString { valueState(sources[1]) })
        val sharing = ValueFlowProofSharing()
        for (owner in owners) assertSame(owner, sharing.executionOwner(owner))
        for (index in calls.indices) {
            val context = owners[index].context as ValueExecutionContext.Callback
            assertEquals(calls.toSet(), context.invocations.map { it.invocation }.toSet())
            assertSame(context.invocations.single { it.invocation == calls[index] }, context.selectedInvocation)
        }
    }

    @Test
    fun `selected invocations return only to their own supplier`() {
        val selected = selectedReturns()
        val (owners, calls, frame) = selected
        val sources = selected.sources
        val targets = selected.targets
        for (index in calls.indices) {
            val context = owners[index].context as ValueExecutionContext.Callback
            val evidence =
                ValueSourceTransfer.fromCompiler(ValueSourceOperation.CallbackReturn(context.selectedInvocation, frame))
            assertEquals(
                targets[index],
                ValueTransfer.fromCompiler(
                        sources[index],
                        targets[index],
                        ValueTransferKind.SOURCE_CALL_RETURN,
                        evidence,
                    )
                    .flowRefined()
                    .target,
            )
            val other = 1 - index
            assertEquals(
                Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
                ValueTransfer.fromCompiler(
                    sources[other],
                    targets[index],
                    ValueTransferKind.SOURCE_CALL_RETURN,
                    evidence,
                ),
            )
            assertEquals(
                Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION),
                owners[other].supplierOf(context.selectedInvocation),
            )
        }
    }

    @Test
    fun `unselected repeated restoration and selections outside exact complete graph fail closed`() {
        val f = CallbackInvocationFlowFixture()
        val calls = invocations(f)
        val graph = graph(f, calls)
        val missing = CallbackParameterInvocation.fromCompiler(f.occurrence(360, 370), calls[0].owner).flowRefined()
        val wrongCall = ValueInvocation.fromCompiler(f.caller, f.range(21, 81), f.target).flowRefined()
        val wrongSupply =
            CallbackArgumentBinding.fromCompiler(
                    wrongCall,
                    f.supplyingOwner,
                    f.binding.position,
                    f.binding.parameter,
                )
                .flowRefined()
        for (candidate in
            listOf(
                ValueExecutionOwner.callback(f.caller, f.body, graph),
                ValueExecutionOwner.callback(f.caller, f.body, graph, f.binding, missing),
                ValueExecutionOwner.callback(f.caller, f.body, graph, wrongSupply, calls[0]),
            )) {
            assertEquals(Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION), candidate)
        }
        val unique = graph(f, listOf(calls[0]))
        assertEquals(
            ValueExecutionOwner.callback(f.caller, f.body, unique).flowRefined(),
            ValueExecutionOwner.callback(f.caller, f.body, unique, f.binding, calls[0]).flowRefined(),
        )
    }

    private data class SelectedReturns(
        val owners: List<ValueExecutionOwner>,
        val calls: List<CallbackParameterInvocation>,
        val frame: ValueCallFrame,
        val sources: List<ValueSite>,
        val targets: List<ValueSite>,
    )

    private fun selectedReturns(): SelectedReturns {
        val f = CallbackInvocationFlowFixture()
        val calls = invocations(f)
        val graph = graph(f, calls)
        val owners = calls.map { ValueExecutionOwner.callback(f.caller, f.body, graph, f.binding, it).flowRefined() }
        val frame = ValueCallFrame.direct(f.invocation, ValueExecutionOwner.declaration(f.target)).flowRefined()
        val stack = ValueCallStack.Empty.enter(frame).flowRefined()
        val sources = owners.map {
            ValueSite.fromCompiler(
                    f.caller,
                    f.range(40, 50),
                    ValueRole.Return,
                    it,
                    flowPath(ValuePathSegment.Element),
                    stack,
                )
                .flowRefined()
        }
        val targets = calls.map {
            ValueSite.fromCompiler(
                    f.target,
                    it.occurrence.range,
                    ValueRole.ExpressionResult,
                    valuePath = flowPath(ValuePathSegment.Element),
                    callStack = stack,
                )
                .flowRefined()
        }
        return SelectedReturns(owners = owners, calls = calls, frame = frame, sources = sources, targets = targets)
    }

    private fun invocations(f: CallbackInvocationFlowFixture): List<CallbackParameterInvocation> =
        listOf(f.occurrence(320, 330), f.occurrence(340, 350)).map {
            CallbackParameterInvocation.fromCompiler(
                    it,
                    RelationCallableBody.Named.fromCompiler(f.target.evidence).flowRefined(),
                )
                .flowRefined()
        }

    private fun graph(f: CallbackInvocationFlowFixture, calls: List<CallbackParameterInvocation>) =
        CompleteCallbackFlowGraph.admit(
                CallbackInvocationFlow.fromCompiler(
                        f.lease.identity,
                        f.body,
                        CallbackBindingEvidence.Bound(f.binding),
                        calls,
                        emptySet(),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .flowRefined()
            )
            .flowRefined()
}
