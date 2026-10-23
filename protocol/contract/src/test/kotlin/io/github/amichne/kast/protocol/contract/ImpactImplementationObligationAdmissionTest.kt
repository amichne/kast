package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactImplementationObligationAdmissionTest {
    @Test
    fun `expression refusal encodes its exact kind and range and rejects evidence outside its declaration`() {
        val f = ImpactFindingFixture()
        val evidence =
            ImpactFlowObligationEvidenceDocument.Expression(
                f.text("KtNameReferenceExpression"),
                ImpactSourceRangeDocument(f.offset(35), f.offset(36)),
            )
        val witness =
            ImpactWitnessDocument.FlowObligation(
                f.count(0),
                f.count(0),
                f.producer,
                ImpactFlowUnsupportedDocument.NESTED_EXECUTION,
                evidence,
            )
        assertTrue(witness.hasSourceBasis(f.declaration.basis))
        val encoded =
            Json.encodeToJsonElement(ImpactWitnessDocument.serializer(), witness)
                .jsonObject
                .getValue("evidence")
                .jsonObject
        assertEquals(setOf("type", "kind", "range"), encoded.keys)
        assertEquals("EXPRESSION", encoded.getValue("type").jsonPrimitive.content)
        assertEquals("KtNameReferenceExpression", encoded.getValue("kind").jsonPrimitive.content)
        val range = encoded.getValue("range").jsonObject
        assertEquals(setOf("start", "end"), range.keys)
        assertEquals("35", range.getValue("start").jsonPrimitive.content)
        assertEquals("36", range.getValue("end").jsonPrimitive.content)
        for (invalid in
            listOf(
                evidence.copy(range = ImpactSourceRangeDocument(f.offset(99), f.offset(101))),
                evidence.copy(range = ImpactSourceRangeDocument(f.offset(35), f.offset(35))),
                evidence.copy(kind = f.text("KtExpression\nsource payload")),
            )) assertFalse(witness.copy(evidence = invalid).hasSourceBasis(f.declaration.basis))
    }

    @Test
    fun `path and compact finding reject dispatch evidence for another callable`() {
        val f = ImplementationShapeFixture()
        val evidence = ImpactFlowObligationEvidenceDocument.ImplementationDispatch(f.dispatch)
        val terminal =
            ImpactPathTerminalDocument.UnresolvedFlow(
                f.argument,
                ImpactFlowUnsupportedDocument.UNMODELED_CALL,
                evidence,
            )
        val path =
            ImpactPathDocument(
                producer = f.argument,
                steps = f.values.bounded(emptyList()),
                representation = ImpactRepresentationEvidenceDocument.NotModeled,
                terminal = terminal,
            )
        val finding =
            f.values
                .finding()
                .copy(
                    producer = f.argument,
                    destination = f.argument,
                    terminal = ImpactFindingTerminalDocument.UnresolvedFlow(terminal.cause, evidence),
                )
        assertTrue(path.hasValidPeerContinuation(f.basis))
        assertTrue(ImpactWitnessDocument.Finding(finding).hasSourceBasis(f.basis))
        val wrong = evidence.copy(dispatch = f.dispatch.copy(question = f.dispatch.question.copy(subject = f.other)))
        assertFalse(path.copy(terminal = terminal.copy(evidence = wrong)).hasValidPeerContinuation(f.basis))
        assertFalse(
            ImpactWitnessDocument.Finding(
                    finding.copy(terminal = ImpactFindingTerminalDocument.UnresolvedFlow(terminal.cause, wrong))
                )
                .hasSourceBasis(f.basis)
        )
    }
}
