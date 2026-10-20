package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ValueCallbackFrameOracleTest {
    @Test
    fun `a callback frame keeps the exact bound flow selected invoke and supplying frame`() {
        val case = ValueCallbackOracleCase()
        val frame = case.frame()
        val target = frame.target as ValueCallbackTarget
        assertSame(case.invocation, frame.invocation)
        assertSame(case.callback, frame.callee)
        assertSame(case.flow, target.flow)
        assertSame(case.selected, target.selected)
        assertSame(case.supplyingFrame, target.supplyingFrame)
        assertEquals(SourceLessCallableModuleKind.BUILTINS, case.invocation.callable.moduleKind)
        assertEquals("kotlin.Function1.invoke", case.invocation.signature.qualifiedIdentity.value)
    }

    @Test
    fun `a sibling anonymous body cannot substitute for the supplied body`() {
        val case = ValueCallbackOracleCase()
        val other = callbackOracleOwner(case.host, 70, 90)
        assertEquals(
            Refinement.Rejected(ValueCallBindingFailure.CALLBACK_PROOF_MISMATCH),
            ValueCallFrame.callback(
                invocation = case.invocation,
                callee = other,
                flow = case.flow,
                selected = case.selected,
                supplyingFrame = case.supplyingFrame,
            ),
        )
    }

    @Test
    fun `the same builtin callable at another range cannot substitute for the selected invoke`() {
        val case = ValueCallbackOracleCase()
        val other =
            ValueFunctionInvocation.fromCompiler(case.mapper, flowRange(530, 550), case.invocation.callable)
                .flowRefined()
        assertEquals(case.invocation.callable, other.callable)
        assertNotEquals(case.invocation.identity, other.identity)
        assertEquals(
            Refinement.Rejected(ValueCallBindingFailure.CALLBACK_PROOF_MISMATCH),
            ValueCallFrame.callback(
                invocation = other,
                callee = case.callback,
                flow = case.flow,
                selected = case.selected,
                supplyingFrame = case.supplyingFrame,
            ),
        )
    }

    @Test
    fun `an invoke absent from the bound flow cannot supply a callback frame`() {
        val case = ValueCallbackOracleCase()
        val other =
            ValueFunctionInvocation.fromCompiler(case.mapper, flowRange(530, 550), case.invocation.callable)
                .flowRefined()
        val selected =
            CallbackParameterInvocation.fromCompiler(
                    RelationOccurrence.fromBoundary(case.mapper.file, 530, 550).flowRefined(),
                    case.selected.owner,
                )
                .flowRefined()
        assertEquals(
            Refinement.Rejected(ValueCallBindingFailure.CALLBACK_PROOF_MISMATCH),
            ValueCallFrame.callback(
                invocation = other,
                callee = case.callback,
                flow = case.flow,
                selected = selected,
                supplyingFrame = case.supplyingFrame,
            ),
        )
    }

    @Test
    fun `foreign callback body flow or supplying frame rejects before frame admission`() {
        val local = ValueCallbackOracleCase()
        val foreign = ValueCallbackOracleCase(generation = 2)
        rejectBasis(local, foreign.callback, local.flow, local.supplyingFrame)
        rejectBasis(local, local.callback, foreign.flow, local.supplyingFrame)
        rejectBasis(local, local.callback, local.flow, foreign.supplyingFrame)
    }

    @Test
    fun `a second supplier of the same mapper cannot consume the first suppliers callback proof`() {
        val first = ValueCallbackOracleCase()
        val second = ValueCallbackOracleCase(supplyStart = 10)
        assertEquals(first.supplyingFrame.callee, second.supplyingFrame.callee)
        assertNotEquals(first.supplyingFrame.invocation, second.supplyingFrame.invocation)
        assertEquals(
            Refinement.Rejected(ValueCallBindingFailure.CALLBACK_PROOF_MISMATCH),
            ValueCallFrame.callback(
                invocation = first.invocation,
                callee = first.callback,
                flow = first.flow,
                selected = first.selected,
                supplyingFrame = second.supplyingFrame,
            ),
        )
    }

    @Test
    fun `a named direct call cannot use lexical containment to enter an anonymous body`() {
        val case = ValueCallbackOracleCase()
        val named = ValueInvocation.fromCompiler(case.host, flowRange(20, 100), case.host).flowRefined()
        assertEquals(named.callable.valueIdentity, case.callback.enclosing.valueIdentity)
        assertEquals(
            Refinement.Rejected(ValueCallBindingFailure.CALLABLE_MISMATCH),
            ValueCallFrame.direct(named, case.callback),
        )
    }

    @Test
    fun `a same named invoke outside Kotlin FunctionN is not a builtin function invocation`() {
        val case = ValueCallbackOracleCase()
        assertEquals(
            Refinement.Rejected(ValueFunctionInvocationFailure.NOT_BUILTIN_INVOKE),
            ValueFunctionInvocation.fromCompiler(
                case.mapper,
                flowRange(500, 520),
                callbackOracleBuiltin("fixture.Function1.invoke"),
            ),
        )
    }

    private fun rejectBasis(
        case: ValueCallbackOracleCase,
        callee: ValueExecutionOwner,
        flow: CallbackInvocationFlow,
        supplyingFrame: ValueCallFrame,
    ) {
        assertEquals(
            Refinement.Rejected(ValueCallBindingFailure.BASIS_MISMATCH),
            ValueCallFrame.callback(
                invocation = case.invocation,
                callee = callee,
                flow = flow,
                selected = case.selected,
                supplyingFrame = supplyingFrame,
            ),
        )
    }
}
