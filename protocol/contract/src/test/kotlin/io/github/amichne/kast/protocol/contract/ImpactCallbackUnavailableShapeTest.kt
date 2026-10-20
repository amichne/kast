package io.github.amichne.kast.protocol.contract

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactCallbackUnavailableShapeTest {
    @Test
    fun `raw contract failure rejects a contradictory outer cause`() {
        val f = CallbackIdentityFixture()
        val evidence =
            ImpactFlowObligationEvidenceDocument.CallbackContractRejected(
                QueryCallbackFlowFailureDocument.INVALID_FORWARDING_PATH
            )
        assertTrue(evidence.admitsObligation(f.argument, f.basis, ImpactFlowUnsupportedDocument.CALLBACK_FLOW_UNPROVEN))
        assertFalse(evidence.admitsObligation(f.argument, f.basis, ImpactFlowUnsupportedDocument.EXTERNAL_CALL))
        val limit = evidence.copy(cause = QueryCallbackFlowFailureDocument.PROOF_LIMIT_REACHED)
        assertTrue(
            limit.admitsObligation(f.argument, f.basis, ImpactFlowUnsupportedDocument.CALLBACK_PROOF_LIMIT_REACHED)
        )
        assertFalse(limit.admitsObligation(f.argument, f.basis, ImpactFlowUnsupportedDocument.CALLBACK_FLOW_UNPROVEN))
    }

    @Test
    fun `raw callback refusal rejects a contradictory outer cause`() {
        val f = CallbackIdentityFixture()
        val evidence =
            ImpactFlowObligationEvidenceDocument.CallbackUnavailable(QueryCallbackFlowCauseDocument.PARAMETER_ESCAPES)
        assertTrue(evidence.admitsObligation(f.argument, f.basis, ImpactFlowUnsupportedDocument.CALLBACK_FLOW_UNPROVEN))
        assertFalse(evidence.admitsObligation(f.argument, f.basis, ImpactFlowUnsupportedDocument.EXTERNAL_CALL))
        assertFalse(
            evidence.admitsObligation(
                f.argument.copy(role = ImpactValueRoleDocument.ExpressionResult),
                f.basis,
                ImpactFlowUnsupportedDocument.CALLBACK_FLOW_UNPROVEN,
            )
        )
    }

    @Test
    fun `return refusal needs an actual callback frame`() {
        val f = CallbackIdentityFixture()
        val evidence =
            ImpactFlowObligationEvidenceDocument.CallbackUnavailable(QueryCallbackFlowCauseDocument.PROOF_LIMIT_REACHED)
        val owner = f.frame.callee.executionOwner as ImpactExecutionOwnerDocument.Anonymous
        val returned =
            f.argument.copy(
                enclosing = f.frame.callee.enclosing,
                range = owner.body.range,
                role = ImpactValueRoleDocument.Return,
                executionOwner = owner,
            )
        assertFalse(
            evidence.admitsObligation(returned, f.basis, ImpactFlowUnsupportedDocument.CALLBACK_PROOF_LIMIT_REACHED)
        )
        val stack =
            (BoundedProtocolList.create(listOf(f.target.supplyingFrame.asFrame(), f.frame))
                    as io.github.amichne.kast.kernel.Refinement.Refined)
                .value
        assertTrue(
            evidence.admitsObligation(
                returned.copy(callStack = stack),
                f.basis,
                ImpactFlowUnsupportedDocument.CALLBACK_PROOF_LIMIT_REACHED,
            )
        )
    }
}
