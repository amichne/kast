package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ValueSeedCallbackQuestionTest {
    @Test
    fun `a selected source subject preserves the same current resolved supplier proof`() {
        val f = ValueCallbackOracleCase()
        val owner = RelationEndpoint.subject(SymbolSelector.issue(f.mapper.lease, f.mapper.scope, f.mapper.evidence))
        val invoke =
            ValueFunctionInvocation.fromCompiler(owner, f.invocation.range, f.invocation.callable).flowRefined()
        val source =
            ValueSite.fromCompiler(
                    enclosing = owner,
                    range = f.argument().range,
                    role = ValueRole.FunctionArgument(invoke, callbackOracleIndex()),
                )
                .flowRefined()
        val obligation = ValueFlowObligation.fromSeedCallback(source, f.flow).flowRefined()
        assertSame(owner, obligation.site.enclosing)
        assertSame(f.flow, (obligation.evidence as ValueFlowObligationEvidence.CallbackFlow).flow)
    }

    @Test
    fun `an empty seed question preserves the whole qualified flow without a positive edge`() {
        val f = ValueCallbackOracleCase()
        val source = f.argument(ValueCallStack.Empty)
        val flow = qualified(f, emptyList())
        val obligation = ValueFlowObligation.fromSeedCallback(source, flow).flowRefined()
        assertSame(source, obligation.site)
        assertSame(flow, (obligation.evidence as ValueFlowObligationEvidence.CallbackFlow).flow)
        assertEquals(ValueFlowUnsupportedCause.CALLBACK_FLOW_UNPROVEN, obligation.cause)
        assertEquals(emptyList<CallbackParameterInvocation>(), flow.invocations)
        assertEquals(setOf(CallbackInvocationFlowCause.WORK_LIMIT_REACHED), flow.obligations)
        assertEquals(CallbackInvocationScan.INCOMPLETE, flow.scan)
    }

    @Test
    fun `a seed question preserves both a selected invocation and the outstanding cause`() {
        val f = ValueCallbackOracleCase()
        val flow = qualified(f, listOf(f.selected))
        val obligation = ValueFlowObligation.fromSeedCallback(f.argument(ValueCallStack.Empty), flow).flowRefined()
        assertSame(flow, (obligation.evidence as ValueFlowObligationEvidence.CallbackFlow).flow)
        assertEquals(listOf(f.selected), flow.invocations)
        assertEquals(setOf(CallbackInvocationFlowCause.WORK_LIMIT_REACHED), flow.obligations)
    }

    @Test
    fun `a seed question rejects an active stack and a foreign semantic basis`() {
        val f = ValueCallbackOracleCase()
        assertEquals(
            Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH),
            ValueFlowObligation.fromSeedCallback(f.argument(), f.flow),
        )
        val other = ValueCallbackOracleCase(generation = 2)
        assertEquals(
            Refinement.Rejected(ValueFlowObligationFailure.BASIS_MISMATCH),
            ValueFlowObligation.fromSeedCallback(f.argument(ValueCallStack.Empty), other.flow),
        )
    }

    @Test
    fun `the same callable identity with another full scope cannot supply a seed question`() {
        val f = ValueCallbackOracleCase()
        val original = (f.flow.binding as CallbackBindingEvidence.Bound).binding
        val changed =
            RelationEndpoint.resolve(
                    lease = f.mapper.lease,
                    scope =
                        (f.mapper.scope as SymbolSearchScope.Workspace).copy(libraries = SymbolLibraryPolicy.INCLUDE),
                    evidence = f.mapper.evidence,
                    constraints = f.mapper.constraints,
                )
                .flowRefined()
        val call = ValueInvocation.fromCompiler(f.host, original.invocation.range, changed).flowRefined()
        val binding =
            CallbackArgumentBinding.fromCompiler(
                    invocation = call,
                    invocationOwner = original.invocationOwner,
                    position = original.position,
                    parameter = original.parameter,
                )
                .flowRefined()
        val foreign =
            CallbackInvocationFlow.fromCompiler(
                    basis = f.flow.basis,
                    body = f.flow.body,
                    binding = CallbackBindingEvidence.Bound(binding),
                    invocations = listOf(f.selected),
                    obligations = emptySet(),
                    scan = CallbackInvocationScan.EXHAUSTIVE,
                )
                .flowRefined()
        assertEquals(f.mapper.valueIdentity, changed.valueIdentity)
        assertNotEquals(f.mapper.scope, changed.scope)
        assertEquals(
            Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH),
            ValueFlowObligation.fromSeedCallback(f.argument(ValueCallStack.Empty), foreign),
        )
    }

    private fun qualified(f: ValueCallbackOracleCase, invocations: List<CallbackParameterInvocation>) =
        CallbackInvocationFlow.fromCompiler(
                basis = f.flow.basis,
                body = f.flow.body,
                binding = f.flow.binding,
                invocations = invocations,
                obligations = setOf(CallbackInvocationFlowCause.WORK_LIMIT_REACHED),
                scan = CallbackInvocationScan.INCOMPLETE,
            )
            .flowRefined()
}
