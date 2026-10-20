package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactSeedCallbackReturnQuestionTest {
    @Test
    fun `an empty anonymous return keeps its qualified bound question without an active frame`() {
        val f = ImpactSeedCallbackReturnCase()
        val evidence = ImpactFlowObligationEvidenceDocument.CallbackFlow(f.qualified)
        assertTrue(f.qualified.admitsFlow(f.callback.basis))
        assertFalse(f.source.admitsCallbackQuestion(f.qualified))
        assertTrue(
            evidence.admitsObligation(
                f.source,
                f.callback.basis,
                ImpactFlowUnsupportedDocument.CALLBACK_FLOW_UNPROVEN,
            )
        )
        assertFalse(evidence.admitsObligation(f.source, f.callback.basis, ImpactFlowUnsupportedDocument.EXTERNAL_CALL))
        val encoded = Json.encodeToJsonElement(ImpactFlowObligationEvidenceDocument.serializer(), evidence).jsonObject
        assertEquals(setOf("type", "flow"), encoded.keys)
        val flow = encoded.getValue("flow").jsonObject
        assertEquals("INCOMPLETE", flow.getValue("scan").jsonPrimitive.content)
        assertEquals(
            listOf("WORK_LIMIT_REACHED"),
            flow.getValue("obligations").jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(flow.getValue("invocations").jsonArray.isEmpty())
        assertEquals("BOUND", flow.getValue("binding").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `a seeded return question rejects another host body basis or supplier execution`() {
        val f = ImpactSeedCallbackReturnCase()
        val evidence = ImpactFlowObligationEvidenceDocument.CallbackFlow(f.qualified)
        fun accepts(site: ImpactValueSiteReferenceDocument) =
            evidence.admitsObligation(
                site,
                f.callback.basis,
                ImpactFlowUnsupportedDocument.CALLBACK_FLOW_UNPROVEN,
            )
        assertTrue(accepts(f.source))
        assertFalse(
            accepts(
                f.source.copy(
                    enclosing = f.source.enclosing.copy(compilerIdentity = f.callback.frame.enclosing.compilerIdentity)
                )
            )
        )
        assertFalse(accepts(f.source.copy(executionOwner = ImpactExecutionOwnerDocument.Declaration)))
        assertFalse(accepts(f.source.copy(role = ImpactValueRoleDocument.ExpressionResult)))
        val otherBody = f.callback.anonymousBody(41, 80)
        assertFalse(
            ImpactFlowObligationEvidenceDocument.CallbackFlow(f.qualified.copy(body = otherBody))
                .admitsObligation(f.source, f.callback.basis, ImpactFlowUnsupportedDocument.CALLBACK_FLOW_UNPROVEN)
        )
        val foreign = f.callback.basis.copy(generation = ImpactEvidenceRevisionDocument.parse(8).value())
        assertFalse(
            evidence.admitsObligation(
                f.source.copy(enclosing = f.source.enclosing.copy(basis = foreign)),
                f.callback.basis,
                ImpactFlowUnsupportedDocument.CALLBACK_FLOW_UNPROVEN,
            )
        )
        val binding = f.qualified.binding as QueryCallbackBindingWireDocument.Bound
        val nested = binding.copy(invocation = binding.invocation.copy(executionOwner = f.source.executionOwner))
        assertFalse(
            ImpactFlowObligationEvidenceDocument.CallbackFlow(f.qualified.copy(binding = nested))
                .admitsObligation(f.source, f.callback.basis, ImpactFlowUnsupportedDocument.CALLBACK_FLOW_UNPROVEN)
        )
    }

    @Test
    fun `empty anonymous return refusals preserve both raw variants and exact proof limit cause`() {
        val f = ImpactSeedCallbackReturnCase()
        val unavailable =
            ImpactFlowObligationEvidenceDocument.CallbackUnavailable(QueryCallbackFlowCauseDocument.PROOF_LIMIT_REACHED)
        val rejected =
            ImpactFlowObligationEvidenceDocument.CallbackContractRejected(
                QueryCallbackFlowFailureDocument.PROOF_LIMIT_REACHED
            )
        for (evidence in listOf(unavailable, rejected)) assertRawRefusal(f, evidence)
        assertEquals(
            "CALLBACK_UNAVAILABLE",
            Json.encodeToJsonElement(
                    ImpactFlowObligationEvidenceDocument.serializer(),
                    unavailable,
                )
                .jsonObject
                .getValue("type")
                .jsonPrimitive
                .content,
        )
        assertEquals(
            "CALLBACK_CONTRACT_REJECTED",
            Json.encodeToJsonElement(
                    ImpactFlowObligationEvidenceDocument.serializer(),
                    rejected,
                )
                .jsonObject
                .getValue("type")
                .jsonPrimitive
                .content,
        )
    }

    private fun assertRawRefusal(
        f: ImpactSeedCallbackReturnCase,
        evidence: ImpactFlowObligationEvidenceDocument,
    ) {
        assertTrue(
            evidence.admitsObligation(
                f.source,
                f.callback.basis,
                ImpactFlowUnsupportedDocument.CALLBACK_PROOF_LIMIT_REACHED,
            )
        )
        assertFalse(
            evidence.admitsObligation(
                f.source,
                f.callback.basis,
                ImpactFlowUnsupportedDocument.CALLBACK_FLOW_UNPROVEN,
            )
        )
        assertFalse(
            evidence.admitsObligation(
                f.source.copy(executionOwner = ImpactExecutionOwnerDocument.Declaration),
                f.callback.basis,
                ImpactFlowUnsupportedDocument.CALLBACK_PROOF_LIMIT_REACHED,
            )
        )
        val encoded = Json.encodeToJsonElement(ImpactFlowObligationEvidenceDocument.serializer(), evidence).jsonObject
        assertEquals(setOf("type", "cause"), encoded.keys)
        assertEquals("PROOF_LIMIT_REACHED", encoded.getValue("cause").jsonPrimitive.content)
    }
}

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
