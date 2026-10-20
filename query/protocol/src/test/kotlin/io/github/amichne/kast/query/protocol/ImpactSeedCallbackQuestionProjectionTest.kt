package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.ImpactFlowObligationEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowUnsupportedDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueCallStack
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowObligationFailure
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowStepFailure
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ImpactSeedCallbackQuestionProjectionTest {
    @Test
    fun `explicit seed diagnostics keep the original flow and finite qualification through projection`() {
        val f = ImpactCallbackProjectionCase(qualified = true)
        val source = f.argument.withState(callStack = ValueCallStack.Empty).value()
        assertEquals(
            Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH),
            ValueFlowObligation.fromCallback(source, f.flow),
        )
        val obligation = ValueFlowObligation.fromSeedCallback(source, f.flow).value()
        assertSame(source, obligation.site)
        val evidence =
            obligation.evidence.impactDocument(f.references, source.enclosing.lease).value()
                as ImpactFlowObligationEvidenceDocument.CallbackFlow
        assertEquals(ImpactFlowUnsupportedDocument.CALLBACK_FLOW_UNPROVEN, obligation.cause.impactDocument())
        assertEquals(listOf(QueryCallbackFlowCauseDocument.WORK_LIMIT_REACHED), evidence.flow.obligations)
        assertEquals(1, evidence.flow.invocations.size)
        assertEquals(
            Refinement.Rejected(ValueFlowStepFailure.UNRESOLVED_OBLIGATIONS),
            ValueFlowStep.fromCompiler(
                source = source,
                transfers = emptyList(),
                obligations = listOf(obligation),
                terminal = ValueFlowTerminal.SupportedDomainExhausted,
                domain = request(f),
                examinedWorkUnits = RelationWorkCount.parse(0).value(),
            ),
        )
    }

    private fun request(f: ImpactCallbackProjectionCase) =
        RelationRequest.start(
            subject = f.argument.enclosing,
            meaning = RelationMeaning.Callees,
            budget =
                RelationBudget(
                    ResourceBudget(
                        ResultLimit.parse(4).value(),
                        WorkUnitLimit.parse(100).value(),
                        ElapsedTimeLimitMillis.parse(1000).value(),
                    ),
                    RelationByteLimit.parse(1_000_000).value(),
                ),
        )
}

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
