package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Seed diagnostics preserve typed qualifications without granting a transfer or native authority. */
class ImpactSeedCallbackQuestionTest {
    @Test
    fun `an empty declaration seed keeps its exact bound supplier and outstanding cause`() {
        val f = CallbackIdentityFixture()
        val source = f.argument.copy(callStack = EmptyImpactCallStack)
        val flow = qualified(f)
        val evidence = ImpactFlowObligationEvidenceDocument.CallbackFlow(flow)
        assertTrue(flow.admitsFlow(f.basis))
        assertFalse(source.admitsCallbackQuestion(flow))
        assertTrue(evidence.admitsObligation(source, f.basis, ImpactFlowUnsupportedDocument.CALLBACK_FLOW_UNPROVEN))
        assertFalse(evidence.admitsObligation(source, f.basis, ImpactFlowUnsupportedDocument.EXTERNAL_CALL))
        val encoded = Json.encodeToJsonElement(ImpactFlowObligationEvidenceDocument.serializer(), evidence).jsonObject
        assertEquals(setOf("type", "flow"), encoded.keys)
        assertEquals("CALLBACK_FLOW", encoded.getValue("type").jsonPrimitive.content)
        val proof = encoded.getValue("flow").jsonObject
        assertEquals("BOUND", proof.getValue("binding").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("INCOMPLETE", proof.getValue("scan").jsonPrimitive.content)
        assertEquals(
            listOf("WORK_LIMIT_REACHED"),
            proof.getValue("obligations").jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(proof.getValue("invocations").jsonArray.isEmpty())
    }

    @Test
    fun `a seed diagnostic rejects another callable basis and nondeclaration execution`() {
        val f = CallbackIdentityFixture()
        val source = f.argument.copy(callStack = EmptyImpactCallStack)
        val evidence = ImpactFlowObligationEvidenceDocument.CallbackFlow(qualified(f))
        fun accepts(site: ImpactValueSiteReferenceDocument) =
            evidence.admitsObligation(site, f.basis, ImpactFlowUnsupportedDocument.CALLBACK_FLOW_UNPROVEN)
        assertTrue(accepts(source))
        assertFalse(
            accepts(
                source.copy(
                    enclosing =
                        source.enclosing.copy(compilerIdentity = f.target.supplyingFrame.enclosing.compilerIdentity)
                )
            )
        )
        assertFalse(
            accepts(
                source.copy(
                    enclosing =
                        source.enclosing.copy(
                            basis = f.basis.copy(generation = ImpactEvidenceRevisionDocument.parse(8).value())
                        )
                )
            )
        )
        assertFalse(accepts(source.copy(executionOwner = f.frame.callee.executionOwner)))
        assertFalse(accepts(source.copy(role = ImpactValueRoleDocument.ExpressionResult)))
    }

    private fun qualified(f: CallbackIdentityFixture) =
        f.flow.copy(
            invocations = emptyList(),
            obligations = listOf(QueryCallbackFlowCauseDocument.WORK_LIMIT_REACHED),
            scan = QueryCallbackInvocationScanDocument.INCOMPLETE,
        )
}

private fun <T> io.github.amichne.kast.kernel.Refinement<T, *>.value(): T =
    (this as io.github.amichne.kast.kernel.Refinement.Refined).value
