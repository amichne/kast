package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactConsumerOutcomeDocument
import io.github.amichne.kast.protocol.contract.ImpactFindingProvenanceDocument
import io.github.amichne.kast.protocol.contract.ImpactFindingRepresentationDocument
import io.github.amichne.kast.protocol.contract.ImpactFindingTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactReadRejectionDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationCurrentDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationUnknownDocument
import io.github.amichne.kast.query.contract.QueryImpactReadRejection
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.relation.contract.BoundaryArrival
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.BoundaryTerminalMeaning
import io.github.amichne.kast.relation.contract.BoundaryUnresolvedReason
import io.github.amichne.kast.relation.contract.ModelValuePosition
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowStepFailure
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImpactFindingQualificationTest {
    @Test
    fun `compact consumer retains expected state origin and every distinct current outcome`() {
        val fixture = ImpactFindingModelFixture()
        val input = fixture.site(31, 39, ValueRole.Argument(fixture.call, ValueArgumentPosition.parse(0).value()))
        val arrived =
            fixture.origin
                .transfer(
                    ValueTransfer.fromCompiler(
                            fixture.producer,
                            input,
                            ValueTransferKind.ARGUMENT,
                        )
                        .value()
                )
                .value()
        for ((expected, outcome) in
            listOf(
                "HIPED" to ImpactConsumerOutcomeDocument.SATISFIED,
                "PLAINTEXT" to ImpactConsumerOutcomeDocument.DIFFERENT,
            )) {
            val rule =
                RepresentationRule.ConsumerExpectation.admit(
                        fixture.rule("consumer"),
                        fixture.bind(ModelValuePosition.Argument(ValueArgumentPosition.parse(0).value())),
                        fixture.domain.state(fixture.id(expected)).value(),
                    )
                    .value()
            val terminal =
                QueryImpactTerminal.Consumer(arrived.expect(rule).value()).findingDocument().value()
                    as ImpactFindingTerminalDocument.Consumer
            assertEquals(outcome, terminal.outcome)
            assertEquals(expected, terminal.expectedState.state.value)
            assertEquals("consumer", terminal.reference.rule.value)
        }
        val representation =
            QueryImpactRepresentation.Present(arrived).findingDocument().value()
                as ImpactFindingRepresentationDocument.Present
        val origin =
            representation.branches.values.single().provenance.values.single() as ImpactFindingProvenanceDocument.Origin
        assertEquals("HIPED", origin.state.state.value)
        assertEquals("origin", origin.reference.rule.value)
        assertUnknownConsumer(fixture, arrived, input, origin)
    }

    private fun assertUnknownConsumer(
        fixture: ImpactFindingModelFixture,
        arrived: io.github.amichne.kast.relation.contract.RepresentationEvidence,
        input: io.github.amichne.kast.relation.contract.ValueSite,
        origin: ImpactFindingProvenanceDocument.Origin,
    ) {
        val weakened = arrived.unmodeled(input).value()
        val unknownRule =
            RepresentationRule.ConsumerExpectation.admit(
                    fixture.rule("consumer"),
                    fixture.bind(ModelValuePosition.Argument(ValueArgumentPosition.parse(0).value())),
                    fixture.domain.state(fixture.id("HIPED")).value(),
                )
                .value()
        val unknownTerminal =
            QueryImpactTerminal.Consumer(weakened.expect(unknownRule).value()).findingDocument().value()
                as ImpactFindingTerminalDocument.Consumer
        assertEquals(ImpactConsumerOutcomeDocument.UNKNOWN, unknownTerminal.outcome)
        val branch =
            (QueryImpactRepresentation.Present(weakened).findingDocument().value()
                    as ImpactFindingRepresentationDocument.Present)
                .branches
                .values
                .single()
        assertEquals(listOf(origin, ImpactFindingProvenanceDocument.Unmodeled), branch.provenance.values)
        assertEquals(
            ImpactRepresentationCurrentDocument.Unknown(ImpactRepresentationUnknownDocument.UNMODELED_TRANSFORMATION),
            branch.current,
        )
    }

    @Test
    fun `compact boundary retains model reference unknown provenance and every required obligation`() {
        val fixture = ImpactFindingModelFixture()
        val target = fixture.boundary(fixture.site(50, 60, ValueRole.ExpressionResult))
        val source = fixture.boundary(fixture.producer)
        val model =
            BoundaryModel.Continuation.admit(
                    fixture.rule("wire"),
                    source.reference,
                    target.reference,
                    source,
                    target,
                    emptySet(),
                )
                .value()
        val connected = BoundaryArrival.connect(source, model).value()
        val arrived = fixture.origin.throughBoundary(connected).value()
        val branch =
            (QueryImpactRepresentation.Present(arrived).findingDocument().value()
                    as ImpactFindingRepresentationDocument.Present)
                .branches
                .values
                .single()
        assertEquals(
            listOf("origin", "wire"),
            branch.provenance.values.map {
                when (it) {
                    is ImpactFindingProvenanceDocument.Origin -> it.reference.rule.value
                    is ImpactFindingProvenanceDocument.BoundaryModel -> it.reference.rule.value
                    is ImpactFindingProvenanceDocument.ModeledTransfer,
                    is ImpactFindingProvenanceDocument.ModeledTransformation,
                    ImpactFindingProvenanceDocument.Unmodeled -> error("Unexpected model provenance")
                }
            },
        )
        assertEquals(
            ImpactRepresentationCurrentDocument.Unknown(
                ImpactRepresentationUnknownDocument.BOUNDARY_PRESERVATION_UNPROVEN
            ),
            branch.current,
        )
        val unresolved = BoundaryArrival.unresolved(target, BoundaryUnresolvedReason.MISSING_CONSUMER)
        val terminal =
            QueryImpactTerminal.Unresolved.Boundary(unresolved).findingDocument().value()
                as ImpactFindingTerminalDocument.UnresolvedBoundary
        assertEquals(unresolved.obligation.impactDocument().value(), terminal.obligation)
        assertModeledTerminal(fixture, target)
    }

    private fun assertModeledTerminal(
        fixture: ImpactFindingModelFixture,
        target: io.github.amichne.kast.relation.contract.BoundaryPosition,
    ) {
        val rule =
            BoundaryModel.Terminal.admit(
                    fixture.rule("sink"),
                    target.reference,
                    target,
                    BoundaryTerminalMeaning.REVIEWED_RETENTION,
                )
                .value()
        val end = BoundaryArrival.terminal(target, rule).value()
        val modeled =
            QueryImpactTerminal.ModeledTerminal(end).findingDocument().value()
                as ImpactFindingTerminalDocument.ModeledTerminal
        assertEquals(end.obligations.map { it.impactDocument().value() }, modeled.obligations.values)
        assertEquals("sink", modeled.reference.rule.value)
    }

    @Test
    fun `compact read rejection retains all finite native and contract causes and work receipts`() {
        val fixture = ImpactFindingModelFixture()
        for (cause in ValueFlowRejection.entries) {
            val rejection =
                QueryImpactReadRejection.Native(
                    fixture.producer,
                    RelationSearchBoundary.WORKSPACE_EXPANSION,
                    cause,
                    RelationWorkCount.parse(3).value(),
                )
            val projected =
                (QueryImpactTerminal.Unresolved.ReadRejected(rejection).findingDocument().value()
                        as ImpactFindingTerminalDocument.UnresolvedRead)
                    .rejection as ImpactReadRejectionDocument.Native
            assertEquals(cause.name, projected.cause.name)
            assertEquals(3L, projected.examinedWorkUnits.value)
        }
        for (cause in ValueFlowStepFailure.entries) {
            val rejection =
                QueryImpactReadRejection.Contract(
                    fixture.producer,
                    RelationSearchBoundary.RETAINED_SUBJECT,
                    cause,
                    RelationWorkCount.parse(2).value(),
                )
            val projected =
                (QueryImpactTerminal.Unresolved.ReadRejected(rejection).findingDocument().value()
                        as ImpactFindingTerminalDocument.UnresolvedRead)
                    .rejection as ImpactReadRejectionDocument.Contract
            assertEquals(cause.name, projected.cause.name)
            assertEquals(2L, projected.examinedWorkUnits.value)
        }
    }
}

private fun <T> Refinement<T, *>.value(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
