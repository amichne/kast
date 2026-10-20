package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ValueSeedCallbackReturnOracleTest {
    @Test
    fun `an empty stack return reaches the actual invoke and preserves the selected supplier`() {
        val case = SeedCallbackReturnOracleCase()
        val transfer = case.transfer().flowRefined()
        assertSame(case.source, transfer.source)
        assertSame(case.target, transfer.target)
        assertEquals(ValueCallStack.Empty, transfer.source.callStack)
        assertEquals(ValueRole.Return, transfer.source.role)
        assertEquals(case.frame.callee, transfer.source.executionOwner)
        assertEquals(case.callback.invocation.range, transfer.target.range)
        assertEquals(case.callback.invocation.enclosing, transfer.target.enclosing)
        assertEquals(case.callback.invocation.executionOwner, transfer.target.executionOwner)
        assertEquals(ValueRole.ExpressionResult, transfer.target.role)
        assertSame(case.path, transfer.target.valuePath)
        assertSame(case.predicates, transfer.target.predicates)
        assertEquals(listOf(case.callback.supplyingFrame), transfer.target.callStack.frames)
        assertEquals(ValueCallTarget.Direct, case.callback.supplyingFrame.target)
        val evidence = assertInstanceOf(ValueSourceTransfer::class.java, transfer.evidence)
        val operation = assertInstanceOf(ValueSourceOperation.SeedCallbackReturn::class.java, evidence.operation)
        assertSame(case.frame, operation.frame)
    }

    @Test
    fun `a selected qualified callback keeps the exact raw qualifications in its return proof`() {
        val case = SeedCallbackReturnOracleCase()
        val f = case.callback
        val flow =
            CallbackInvocationFlow.fromCompiler(
                    basis = f.flow.basis,
                    body = f.flow.body,
                    binding = f.flow.binding,
                    invocations = listOf(f.selected),
                    obligations = setOf(CallbackInvocationFlowCause.WORK_LIMIT_REACHED),
                    scan = CallbackInvocationScan.INCOMPLETE,
                )
                .flowRefined()
        val frame =
            ValueCallFrame.callback(
                    invocation = f.invocation,
                    callee = f.callback,
                    flow = flow,
                    selected = f.selected,
                    supplyingFrame = f.supplyingFrame,
                )
                .flowRefined()
        val transfer = case.transfer(frame = frame).flowRefined()
        val operation = (transfer.evidence as ValueSourceTransfer).operation as ValueSourceOperation.SeedCallbackReturn
        val proof = operation.frame.target as ValueCallbackTarget
        assertSame(flow, proof.flow)
        assertEquals(setOf(CallbackInvocationFlowCause.WORK_LIMIT_REACHED), proof.flow.obligations)
        assertEquals(CallbackInvocationScan.INCOMPLETE, proof.flow.scan)
        assertEquals(listOf(f.selected), proof.flow.invocations)
        assertEquals(listOf(f.supplyingFrame), transfer.target.callStack.frames)
    }

    @Test
    fun `another proven supplier cannot replace the target stack of the selected route`() {
        val case = SeedCallbackReturnOracleCase()
        val other = ValueCallbackOracleCase(supplyStart = 10)
        val stack = ValueCallStack.Empty.enter(other.supplyingFrame).flowRefined()
        assertEquals(case.callback.callback, other.callback)
        assertEquals(case.callback.invocation, other.invocation)
        case.rejected(target = case.target.withState(callStack = stack).flowRefined())
        case.rejected(frame = other.frame())
        val matched =
            case
                .transfer(
                    target = case.result(other.invocation).withState(callStack = stack).flowRefined(),
                    frame = other.frame(),
                )
                .flowRefined()
        assertEquals(listOf(other.supplyingFrame), matched.target.callStack.frames)
    }

    @Test
    fun `the result cannot move to another real builtin invoke occurrence`() {
        val case = SeedCallbackReturnOracleCase()
        val f = case.callback
        val other =
            ValueFunctionInvocation.fromCompiler(f.mapper, flowRange(530, 550), f.invocation.callable).flowRefined()
        case.rejected(target = case.result(other))
    }

    @Test
    fun `the seed return cannot discard the supplying frame`() {
        val case = SeedCallbackReturnOracleCase()
        case.rejected(target = case.target.withState(callStack = ValueCallStack.Empty).flowRefined())
    }

    @Test
    fun `a callback frame alone cannot replace the actual supplier`() {
        val case = SeedCallbackReturnOracleCase()
        val stack = ValueCallStack.Empty.enter(case.frame).flowRefined()
        case.rejected(target = case.target.withState(callStack = stack).flowRefined())
    }

    @Test
    fun `the result cannot keep an extra active callback frame`() {
        val case = SeedCallbackReturnOracleCase()
        val stack = case.expectedStack.enter(case.frame).flowRefined()
        case.rejected(target = case.target.withState(callStack = stack).flowRefined())
    }

    @Test
    fun `an active callback uses ordinary return rather than the seed operation`() {
        val case = SeedCallbackReturnOracleCase()
        val active = case.source.withState(callStack = case.expectedStack.enter(case.frame).flowRefined()).flowRefined()
        case.rejected(source = active)
        val ordinary =
            ValueTransfer.fromCompiler(
                    source = active,
                    target = case.target,
                    kind = ValueTransferKind.SOURCE_CALL_RETURN,
                    evidence = ValueSourceTransfer.fromCompiler(ValueSourceOperation.Return(case.frame)),
                )
                .flowRefined()
        assertEquals(listOf(case.callback.supplyingFrame), ordinary.target.callStack.frames)
    }

    @Test
    fun `direct evidence cannot forge the seed callback return`() {
        val case = SeedCallbackReturnOracleCase()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(case.source, case.target, ValueTransferKind.SOURCE_CALL_RETURN),
        )
    }

    @Test
    fun `a named direct frame cannot masquerade as a source callback proof`() {
        val case = SeedCallbackReturnOracleCase()
        val direct = case.callback.supplyingFrame
        val source = ValueSite.fromCompiler(case.callback.mapper, flowRange(540, 550), ValueRole.Return).flowRefined()
        val target = direct.invocation.resultSite().withState(callStack = case.expectedStack).flowRefined()
        case.rejected(source = source, target = target, frame = direct)
    }

    @Test
    fun `a reviewed library callback frame cannot masquerade as a source callback proof`() {
        val case = SeedCallbackReturnOracleCase()
        val metadata = ValueReviewedSharingCase(ReviewedValueModel.Let)
        val application = metadata.fresh.applications.single() as ValueModelApplication.ScopeCallbackInput
        val frame = application.frame
        val source =
            ValueSite.fromCompiler(
                    enclosing = frame.callee.enclosing,
                    range = flowRange(54, 56),
                    role = ValueRole.Return,
                    executionOwner = frame.callee,
                )
                .flowRefined()
        case.rejected(source = source, target = frame.invocation.resultSite(), frame = frame)
    }
}
