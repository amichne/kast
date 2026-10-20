package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.ImpactFlowObligationEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowUnsupportedDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowFailureDocument
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure
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
import io.github.amichne.kast.relation.contract.ValueSite
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ImpactSeedCallbackReturnQuestionProjectionTest {
    @Test
    fun `an explicit empty return question keeps the qualified flow without proving completion`() {
        val f = ImpactCallbackProjectionCase(qualified = true)
        val source = returned(f)
        assertEquals(
            Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH),
            ValueFlowObligation.fromCallback(source, f.flow),
        )
        val obligation = ValueFlowObligation.fromSeedCallbackReturn(source, f.flow).value()
        assertSame(source, obligation.site)
        val evidence =
            obligation.evidence.impactDocument(f.references, source.enclosing.lease).value()
                as ImpactFlowObligationEvidenceDocument.CallbackFlow
        assertEquals(ImpactFlowUnsupportedDocument.CALLBACK_FLOW_UNPROVEN, obligation.cause.impactDocument())
        assertEquals(listOf(QueryCallbackFlowCauseDocument.WORK_LIMIT_REACHED), evidence.flow.obligations)
        assertEquals(1, evidence.flow.invocations.size)
        assertSame(
            f.flow.body,
            (obligation.evidence as io.github.amichne.kast.relation.contract.ValueFlowObligationEvidence.CallbackFlow)
                .flow
                .body,
        )
        assertUnresolved(f, source, obligation)
    }

    @Test
    fun `an empty return keeps unavailable and rejected proof limits as finite diagnostics`() {
        val f = ImpactCallbackProjectionCase()
        val source = returned(f)
        val unavailable =
            ValueFlowObligation.fromCallbackUnavailable(
                    source,
                    CallbackInvocationFlowCause.PROOF_LIMIT_REACHED,
                )
                .value()
        val rejected =
            ValueFlowObligation.fromCallbackContractRejected(
                    source,
                    CallbackInvocationFlowFailure.PROOF_LIMIT_REACHED,
                )
                .value()
        val unavailableDocument =
            unavailable.evidence.impactDocument(f.references, source.enclosing.lease).value()
                as ImpactFlowObligationEvidenceDocument.CallbackUnavailable
        val rejectedDocument =
            rejected.evidence.impactDocument(f.references, source.enclosing.lease).value()
                as ImpactFlowObligationEvidenceDocument.CallbackContractRejected
        assertEquals(QueryCallbackFlowCauseDocument.PROOF_LIMIT_REACHED, unavailableDocument.cause)
        assertEquals(QueryCallbackFlowFailureDocument.PROOF_LIMIT_REACHED, rejectedDocument.cause)
        assertEquals(ImpactFlowUnsupportedDocument.CALLBACK_PROOF_LIMIT_REACHED, unavailable.cause.impactDocument())
        assertEquals(ImpactFlowUnsupportedDocument.CALLBACK_PROOF_LIMIT_REACHED, rejected.cause.impactDocument())
        assertUnresolved(f, source, unavailable)
        assertUnresolved(f, source, rejected)
        val escape =
            ValueFlowObligation.fromCallbackUnavailable(
                    source,
                    CallbackInvocationFlowCause.PARAMETER_ESCAPES,
                )
                .value()
        val escapeDocument =
            escape.evidence.impactDocument(f.references, source.enclosing.lease).value()
                as ImpactFlowObligationEvidenceDocument.CallbackUnavailable
        assertEquals(QueryCallbackFlowCauseDocument.PARAMETER_ESCAPES, escapeDocument.cause)
        assertEquals(ImpactFlowUnsupportedDocument.CALLBACK_FLOW_UNPROVEN, escape.cause.impactDocument())
    }

    private fun returned(f: ImpactCallbackProjectionCase) =
        f.returned().source.withState(callStack = ValueCallStack.Empty).value()

    private fun assertUnresolved(f: ImpactCallbackProjectionCase, source: ValueSite, obligation: ValueFlowObligation) {
        assertEquals(
            Refinement.Rejected(ValueFlowStepFailure.UNRESOLVED_OBLIGATIONS),
            ValueFlowStep.fromCompiler(
                source,
                emptyList(),
                listOf(obligation),
                ValueFlowTerminal.SupportedDomainExhausted,
                request(f),
                RelationWorkCount.parse(0).value(),
            ),
        )
    }

    private fun request(f: ImpactCallbackProjectionCase) =
        RelationRequest.start(
            subject = f.frame.callee.enclosing,
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
