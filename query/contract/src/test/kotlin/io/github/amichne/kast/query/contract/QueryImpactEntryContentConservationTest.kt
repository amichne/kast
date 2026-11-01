package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryImpactEntryContentConservationTest {
    @Test
    fun `whole content ledger rejects an omitted slot and unresolved sibling`() {
        val route = ContentEntryRoute()
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.MISSING_BRANCH),
            route.ledger(route.paths.dropLast(1)),
        )
        val missing = ValueFlowObligation(route.source, ValueFlowUnsupportedCause.UNMODELED_CALL)
        val observation = route.fixture.base.observe(route.source, route.transfers, listOf(missing))
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.MISSING_OBLIGATION),
            route.ledger(route.paths, observation),
        )
    }

    @Test
    fun `whole content ledger preserves three derived conditional branches`() {
        val route = ContentEntryRoute()
        assertEquals(3, route.transfers.size)
        assertTrue(route.transfers.all { it.source == route.source })
        assertTrue(route.ledger(route.paths).componentValue().closure is QueryImpactClosure.Unresolved)
        route.paths.forEach {
            assertEquals(QueryImpactClassification.Established(QueryImpactRelationship.DERIVED), it.classification)
        }
        assertTrue(route.transfers.all { it.source.valuePath.segments.isEmpty() })
    }

    @Test
    fun `mapped return remains derived after a getter removes its entry prefix`() {
        val fixture = ReviewedEntryLedgerFixture()
        val transfer = fixture.mappedContentReturn()
        assertTrue(transfer.source.valuePath.segments.isEmpty())
        assertEquals(
            QueryImpactClassification.Established(QueryImpactRelationship.DERIVED),
            fixture.path(transfer).classification,
        )
    }
}

private class ContentEntryRoute {
    val fixture = ReviewedEntryLedgerFixture()
    val transfers = listOf(fixture.contentInput(true), fixture.contentInput(false), fixture.preservedContent())
    val source = transfers.first().source
    private val observation = fixture.base.observe(source, transfers)
    private val ends = transfers.map { fixture.base.observe(it.target) }
    val paths = transfers.zip(ends).map { (transfer, terminal) -> fixture.path(transfer, terminal) }

    fun ledger(
        paths: List<QueryImpactPath>,
        sourceObservation: io.github.amichne.kast.relation.contract.ValueFlowStep = observation,
    ) =
        QueryImpactLedger.fromEvidence(
            listOf(source),
            fixture.base.domain.boundary,
            QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
            emptyList(),
            emptyList(),
            listOf(sourceObservation) + ends,
            paths,
        )
}

private fun ReviewedEntryLedgerFixture.path(
    transfer: ReviewedValueTransfer,
    terminal: ValueFlowStep = base.observe(transfer.target),
): QueryImpactPath =
    QueryImpactPath.fromEvidence(
            transfer.source,
            listOf(QueryImpactStep.ReviewedLibrary(transfer)),
            QueryImpactRepresentation.NotModeled,
            QueryImpactTerminal.SupportedDomainEnd.admit(terminal).componentValue(),
        )
        .componentValue()
