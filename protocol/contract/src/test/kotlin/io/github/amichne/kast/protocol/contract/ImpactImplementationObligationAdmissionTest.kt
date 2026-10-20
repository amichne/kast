package io.github.amichne.kast.protocol.contract

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactImplementationObligationAdmissionTest {
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
