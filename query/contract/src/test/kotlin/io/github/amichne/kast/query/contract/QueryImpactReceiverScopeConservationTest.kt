package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryImpactReceiverScopeConservationTest {
    @Test
    fun `apply ledger rejects omitted callback input and unresolved siblings`() {
        val route = ReceiverScopeRoute()
        assertEquals(Refinement.Rejected(QueryImpactLedgerFailure.MISSING_BRANCH), route.ledger(route.paths.take(1)))
        val unresolved =
            route.fixture.components.observe(
                route.source,
                route.transfers,
                listOf(ValueFlowObligation(route.source, ValueFlowUnsupportedCause.UNMODELED_CALL)),
            )
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.MISSING_OBLIGATION),
            route.ledger(route.paths, unresolved),
        )
    }

    @Test
    fun `apply ledger preserves receiver input separately from qualified result`() {
        val route = ReceiverScopeRoute()
        assertTrue(route.ledger(route.paths).componentValue().closure is QueryImpactClosure.Unresolved)
        assertEquals(2, route.paths.size)
        assertEquals(1, route.transfers[1].target.conditions.facts.size)
        assertTrue(
            route.paths.all {
                it.classification == QueryImpactClassification.Established(QueryImpactRelationship.EXACT)
            }
        )
    }

    @Test
    fun `scope receiver storage keeps composed authority once per identity`() {
        val input = ReviewedReceiverScopeLedgerFixture(ReceiverScopeFamily.WITH).input()
        val graph = QueryImpactRetainedGraph()
        assertTrue(input.storageBytes(graph) > 8L)
        assertEquals(8L, input.storageBytes(graph))
        assertEquals(input.source.valuePath, input.target.valuePath)
    }
}

private class ReceiverScopeRoute {
    val fixture = ReviewedReceiverScopeLedgerFixture(ReceiverScopeFamily.APPLY)
    val transfers = listOf(fixture.input(whole = true), fixture.applyResult())
    val source = transfers.first().source
    private val observed = fixture.components.observe(source, transfers)
    private val ends = transfers.map { fixture.components.observe(it.target) }
    val paths = transfers.zip(ends).map { (transfer, end) -> receiverScopePath(transfer, end) }

    fun ledger(paths: List<QueryImpactPath>, observation: ValueFlowStep = observed) =
        QueryImpactLedger.fromEvidence(
            listOf(source),
            fixture.components.domain.boundary,
            QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
            emptyList(),
            emptyList(),
            listOf(observation) + ends,
            paths,
        )
}

private fun receiverScopePath(transfer: ReviewedValueTransfer, end: ValueFlowStep) =
    QueryImpactPath.fromEvidence(
            transfer.source,
            listOf(QueryImpactStep.ReviewedLibrary(transfer)),
            QueryImpactRepresentation.NotModeled,
            QueryImpactTerminal.SupportedDomainEnd.admit(end).componentValue(),
        )
        .componentValue()
