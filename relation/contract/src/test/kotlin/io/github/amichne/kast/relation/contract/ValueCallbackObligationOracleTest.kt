package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ValueCallbackObligationOracleTest {
    @Test
    fun `a zero invocation flow keeps its exact cause and source without a positive transfer`() {
        val case = ValueCallbackOracleCase()
        val flow = qualifiedFlow(case, emptyList(), setOf(CallbackInvocationFlowCause.NO_INVOCATION_PROVEN))
        val source = case.argument()
        val obligation = ValueFlowObligation.fromCallback(source, flow).flowRefined()
        val evidence = assertInstanceOf(ValueFlowObligationEvidence.CallbackFlow::class.java, obligation.evidence)
        assertSame(source, obligation.site)
        assertSame(flow, evidence.flow)
        assertEquals(ValueFlowUnsupportedCause.CALLBACK_FLOW_UNPROVEN, obligation.cause)
        assertEquals(emptyList<CallbackParameterInvocation>(), evidence.flow.invocations)
        assertEquals(setOf(CallbackInvocationFlowCause.NO_INVOCATION_PROVEN), evidence.flow.obligations)
        assertEquals(CallbackInvocationScan.INCOMPLETE, evidence.flow.scan)
        val step = obligationStep(obligation, ValueFlowTerminal.Unresolved).flowRefined()
        assertEquals(emptyList<ValueTransfer>(), step.transfers)
        assertSame(obligation, step.obligations.single())
        assertEquals(
            Refinement.Rejected(ValueFlowStepFailure.UNRESOLVED_OBLIGATIONS),
            obligationStep(obligation, ValueFlowTerminal.SupportedDomainExhausted),
        )
    }

    @Test
    fun `a qualified nonempty callback flow keeps each selected invocation and original cause`() {
        val case = ValueCallbackOracleCase()
        val causes =
            setOf(
                CallbackInvocationFlowCause.OUTSIDE_DOMAIN,
                CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE,
            )
        val flow = qualifiedFlow(case, listOf(case.selected), causes)
        val source = case.argument()
        val obligation = ValueFlowObligation.fromCallback(source, flow).flowRefined()
        val evidence = assertInstanceOf(ValueFlowObligationEvidence.CallbackFlow::class.java, obligation.evidence)
        assertSame(source, obligation.site)
        assertSame(flow, evidence.flow)
        assertEquals(listOf(case.selected), evidence.flow.invocations)
        assertEquals(causes, evidence.flow.obligations)
        assertEquals(CallbackInvocationScan.INCOMPLETE, evidence.flow.scan)
        assertSame(case.flow.binding, evidence.flow.binding)
    }

    @Test
    fun `a local callback question rejects a foreign flow basis`() {
        val local = ValueCallbackOracleCase()
        val foreign = ValueCallbackOracleCase(generation = 2)
        assertEquals(
            Refinement.Rejected(ValueFlowObligationFailure.BASIS_MISMATCH),
            ValueFlowObligation.fromCallback(local.argument(), foreign.flow),
        )
    }

    @Test
    fun `a callback question needs the exact active supplying frame`() {
        val case = ValueCallbackOracleCase()
        assertEquals(
            Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH),
            ValueFlowObligation.fromCallback(case.argument(ValueCallStack.Empty), case.flow),
        )
        val other = ValueCallbackOracleCase(supplyStart = 10)
        val wrongStack = ValueCallStack.Empty.enter(other.supplyingFrame).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH),
            ValueFlowObligation.fromCallback(case.argument(wrongStack), case.flow),
        )
        assertEquals(
            Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH),
            ValueFlowObligation.fromCallback(case.invocation.resultSite(), case.flow),
        )
    }

    @Test
    fun `a callback return keeps only the flow of its own active callback frame`() {
        val case = ValueCallbackOracleCase()
        val source =
            ValueSite.fromCompiler(
                    enclosing = case.host,
                    range = flowRange(50, 55),
                    role = ValueRole.Return,
                    executionOwner = case.callback,
                    callStack = case.entered().callStack,
                )
                .flowRefined()
        val obligation = ValueFlowObligation.fromCallback(source, case.flow).flowRefined()
        assertSame(source, obligation.site)
        val evidence = assertInstanceOf(ValueFlowObligationEvidence.CallbackFlow::class.java, obligation.evidence)
        assertSame(case.flow, evidence.flow)
        val other = ValueCallbackOracleCase(supplyStart = 10)
        assertEquals(
            Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH),
            ValueFlowObligation.fromCallback(source, other.flow),
        )
    }

    @Test
    fun `an ordinary unresolved callback cause cannot manufacture captured flow evidence`() {
        val source = ValueCallbackOracleCase().argument()
        val ordinary = ValueFlowObligation(source, ValueFlowUnsupportedCause.CALLBACK_FLOW_UNPROVEN)
        assertSame(source, ordinary.site)
        assertEquals(ValueFlowObligationEvidence.None, ordinary.evidence)
    }

    private fun qualifiedFlow(
        case: ValueCallbackOracleCase,
        invocations: List<CallbackParameterInvocation>,
        causes: Set<CallbackInvocationFlowCause>,
    ) =
        CallbackInvocationFlow.fromCompiler(
                basis = case.flow.basis,
                body = case.flow.body,
                binding = case.flow.binding,
                invocations = invocations,
                obligations = causes,
                scan = CallbackInvocationScan.INCOMPLETE,
            )
            .flowRefined()

    private fun obligationStep(obligation: ValueFlowObligation, terminal: ValueFlowTerminal) =
        ValueFlowStep.fromCompiler(
            source = obligation.site,
            transfers = emptyList(),
            obligations = listOf(obligation),
            terminal = terminal,
            domain =
                RelationRequest.start(
                    obligation.site.enclosing,
                    RelationMeaning.Callees,
                    RelationBudget(implementationBudget().resources, RelationByteLimit.parse(1_000_000).flowRefined()),
                ),
            examinedWorkUnits = RelationWorkCount.parse(0).flowRefined(),
        )
}
