package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ValueCallbackForwardingRouteTest {
    @Test
    fun `complete forwarding graph enters supplied lambda and returns to terminal invoker`() {
        val f = ValueCallbackForwardingFixture()
        assertEquals(1, f.graph.edges.filterIsInstance<StaticCallbackEdge.Forward>().size)
        assertEquals(emptySet<CallbackInvocationFlowCause>(), f.flow.obligations)
        val route = ValueCallbackCallRoute.fromActiveStack(f.capture, f.stack).flowRefined()
        assertSame(f.supplierFrame, route.supplierFrame)
        assertSame(f.invocationFrame, route.invocationFrame)
        assertNotEquals(route.supplierFrame.callee, route.invocationFrame.callee)
        val entered = f.entry().flowRefined()
        assertEquals(f.execution, entered.target.executionOwner)
        assertSame(f.stack, entered.target.callStack)
        val returned =
            ValueTransfer.fromCompiler(
                    source = f.returnSource,
                    target = f.returnTarget,
                    kind = ValueTransferKind.SOURCE_CALL_RETURN,
                    evidence = ValueSourceTransfer.fromCompiler(f.returnOperation),
                )
                .flowRefined()
        assertEquals(f.terminal, returned.target.enclosing)
        assertSame(f.stack, returned.target.callStack)
    }

    @Test
    fun `route admits an exact suffix under an ordinary active ancestor`() {
        val f = ValueCallbackForwardingFixture()
        val outer = f.fixture.endpoint(name = "outer", start = 700, end = 900, parameters = emptyList())
        val ancestor =
            ValueCallFrame.direct(
                    ValueInvocation.fromCompiler(outer, flowRange(750, 790), f.fixture.caller).flowRefined(),
                    ValueExecutionOwner.declaration(f.fixture.caller),
                )
                .flowRefined()
        val stack = f.stack(listOf(ancestor, f.supplierFrame, f.invocationFrame))
        assertEquals(stack, f.entry(stack).flowRefined().target.callStack)
        val route = ValueCallbackCallRoute.fromActiveStack(f.capture, stack).flowRefined()
        assertEquals(f.supplierFrame, route.supplierFrame)
    }

    @Test
    fun `entry rejects missing reordered foreign and noncontiguous supplier suffixes`() {
        val f = ValueCallbackForwardingFixture()
        val wrongRoot = f.changedSupplier()
        val stacks =
            listOf(
                ValueCallStack.Empty,
                f.stack(listOf(f.invocationFrame)),
                f.stack(listOf(f.invocationFrame, f.supplierFrame)),
                f.stack(listOf(wrongRoot, f.invocationFrame)),
                f.stack(listOf(f.supplierFrame, wrongRoot, f.invocationFrame)),
            )
        for (stack in stacks) {
            assertEquals(
                Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION),
                ValueCallbackCallRoute.fromActiveStack(f.capture, stack),
            )
            assertEquals(Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH), f.entry(stack))
        }
    }

    @Test
    fun `terminal invoker cannot replace the root supplier in argument entry`() {
        val f = ValueCallbackForwardingFixture()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            f.entry(operation = f.entryOperation.copy(supplierFrame = f.invocationFrame)),
        )
    }

    @Test
    fun `route rejects forwarding another formal even when call frames match`() {
        val f = ValueCallbackForwardingFixture()
        val wrongFormal =
            CallbackParameterIdentity.fromCompiler(
                    f.relay,
                    ValueArgumentPosition.parse(1).flowRefined(),
                    f.fixture.occurrence(250, 270),
                )
                .flowRefined()
        val wrongEdge =
            CallbackParameterForwarding.fromCompiler(wrongFormal, f.forwarding.argument, f.targetBinding).flowRefined()
        val wrongInvocation =
            CallbackParameterInvocation.fromCompiler(
                    occurrence = f.invoked.occurrence,
                    owner = f.invoked.owner,
                    forwardings = listOf(wrongEdge),
                )
                .flowRefined()
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION),
            ValueCallbackCallRoute.fromActiveStack(f.capture.copy(invocation = wrongInvocation), f.stack),
        )
        assertEquals(
            Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_FORWARDING_PATH),
            CallbackInvocationFlow.fromCompiler(
                basis = f.fixture.lease.identity,
                body = f.body,
                binding = CallbackBindingEvidence.Bound(f.binding),
                invocations = listOf(wrongInvocation),
                obligations = emptySet(),
                scan = CallbackInvocationScan.EXHAUSTIVE,
            ),
        )
    }

    @Test
    fun `forwarded capture start cannot invent the absent supplier route on return`() {
        val f = ValueCallbackForwardingFixture()
        val source = f.returnSource.withState(callStack = ValueCallStack.Empty).flowRefined()
        val target = f.returnTarget.withState(callStack = f.stack(listOf(f.invocationFrame))).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(
                source = source,
                target = target,
                kind = ValueTransferKind.SOURCE_CALL_RETURN,
                evidence = ValueSourceTransfer.fromCompiler(f.returnOperation),
            ),
        )
    }
}
