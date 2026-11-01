package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ValueImplementationObligationOracleTest {
    @Test
    fun `zero target qualified dispatch keeps exact limitations without a positive transfer`() {
        val case = obligationCase()
        val obligation =
            ValueFlowObligation.fromImplementation(case.site, ValueFlowUnsupportedCause.UNMODELED_CALL, case.dispatch)
                .flowRefined()
        val evidence =
            assertInstanceOf(ValueFlowObligationEvidence.ImplementationDispatch::class.java, obligation.evidence)
        val coverage = assertInstanceOf(ValueImplementationCoverage.Incomplete::class.java, evidence.dispatch.coverage)
        assertSame(case.site, obligation.site)
        assertEquals(ValueFlowUnsupportedCause.UNMODELED_CALL, obligation.cause)
        assertSame(case.dispatch, evidence.dispatch)
        assertSame(case.compiled.batch, evidence.dispatch.batch)
        assertSame(case.compiled.coverage, coverage.evidence)
        assertEquals(emptyList<RelationFact>(), evidence.dispatch.batch.facts)
        assertEquals(0, coverage.evidence.knownMinimum.value)
        assertEquals(setOf(RelationLimitation.UNRESOLVED_TARGET), coverage.evidence.limitations)
        val step = obligationStep(case, obligation, ValueFlowTerminal.Unresolved).flowRefined()
        assertEquals(emptyList<ValueTransfer>(), step.transfers)
        assertSame(obligation, step.obligations.single())
        assertEquals(ValueFlowTerminal.Unresolved, step.terminal)
        assertEquals(
            Refinement.Rejected(ValueFlowStepFailure.UNRESOLVED_OBLIGATIONS),
            obligationStep(case, obligation, ValueFlowTerminal.SupportedDomainExhausted),
        )
    }

    @Test
    fun `foreign site basis cannot preserve a local zero target investigation`() {
        val case = obligationCase()
        val foreign = obligationCall(generation = 2)
        assertEquals(case.call.callable.valueIdentity, foreign.callable.valueIdentity)
        assertEquals(
            Refinement.Rejected(ValueFlowObligationFailure.BASIS_MISMATCH),
            ValueFlowObligation.fromImplementation(
                obligationArgument(foreign),
                ValueFlowUnsupportedCause.UNMODELED_CALL,
                case.dispatch,
            ),
        )
    }

    @Test
    fun `another callable or an invocation result cannot claim the captured target investigation`() {
        val case = obligationCase()
        val other = obligationArgument(obligationCall(name = "OtherMapper.map"))
        assertEquals(
            Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH),
            ValueFlowObligation.fromImplementation(other, ValueFlowUnsupportedCause.UNMODELED_CALL, case.dispatch),
        )
        assertEquals(
            Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH),
            ValueFlowObligation.fromImplementation(
                case.call.resultSite(),
                ValueFlowUnsupportedCause.UNMODELED_CALL,
                case.dispatch,
            ),
        )
    }

    @Test
    fun `incomplete callback diagnostic keeps the qualified inventory and basis`() {
        val case = obligationCase()
        val failure = ValueCallbackInputFailure.ImplementationIncomplete.fromCompiler(case.dispatch).flowRefined()
        assertSame(case.dispatch, failure.dispatch)
        assertSame(case.compiled.batch, failure.dispatch.batch)
        assertEquals(true, failure.matchesBasis(case.site.basis))
        assertEquals(false, failure.matchesBasis(obligationArgument(obligationCall(generation = 2)).basis))
    }

    @Test
    fun `exhausted inventory cannot enter the incomplete callback diagnostic`() {
        val case = obligationCase()
        val exhausted =
            ValueImplementationDispatch.fromCompiler(RelationCompilation.complete(case.compiled.batch)).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueCallbackInputFailure.ImplementationIncomplete.AdmissionFailure.EXHAUSTED_DISPATCH),
            ValueCallbackInputFailure.ImplementationIncomplete.fromCompiler(exhausted),
        )
    }

    @Test
    fun `ordinary obligations do not manufacture implementation evidence`() {
        val case = obligationCase()
        val ordinary = ValueFlowObligation(case.site, ValueFlowUnsupportedCause.UNMODELED_CALL)
        assertSame(case.site, ordinary.site)
        assertEquals(ValueFlowObligationEvidence.None, ordinary.evidence)
        assertEquals(ValueFlowUnsupportedCause.UNMODELED_CALL, ordinary.cause)
    }
}

private data class ImplementationObligationCase(
    val call: ValueInvocation,
    val site: ValueSite,
    val compiled: RelationCompilation.Qualified,
    val dispatch: ValueImplementationDispatch,
)

private fun obligationCase(): ImplementationObligationCase {
    val call = obligationCall()
    val request = RelationRequest.start(call.callable, RelationMeaning.Implementations, implementationBudget())
    val batch = implementationBatch(request, emptyList())
    val compiled =
        RelationCompilation.qualifiedTerminal(batch, setOf(RelationLimitation.UNRESOLVED_TARGET)).flowRefined()
    return ImplementationObligationCase(
        call,
        obligationArgument(call),
        compiled,
        ValueImplementationDispatch.fromCompiler(compiled).flowRefined(),
    )
}

private fun obligationCall(generation: Long = 1, name: String = "Mapper.map"): ValueInvocation {
    val caller = flowEndpoint("invoke", 0, 200, generation = generation)
    val target = flowEndpoint(name, 300, 400, parameters = listOf("String"), generation = generation)
    return ValueInvocation.fromCompiler(caller, flowRange(20, 50), target).flowRefined()
}

private fun obligationArgument(call: ValueInvocation): ValueSite =
    ValueSite.fromCompiler(
            call.enclosing,
            flowRange(30, 40),
            ValueRole.Argument(call, ValueArgumentPosition.parse(0).flowRefined()),
        )
        .flowRefined()

private fun obligationStep(
    case: ImplementationObligationCase,
    obligation: ValueFlowObligation,
    terminal: ValueFlowTerminal,
): Refinement<ValueFlowStep, ValueFlowStepFailure> =
    ValueFlowStep.fromCompiler(
        source = case.site,
        transfers = emptyList(),
        obligations = listOf(obligation),
        terminal = terminal,
        domain = RelationRequest.start(case.call.enclosing, RelationMeaning.Callees, implementationBudget()),
        examinedWorkUnits = RelationWorkCount.parse(0).flowRefined(),
    )
