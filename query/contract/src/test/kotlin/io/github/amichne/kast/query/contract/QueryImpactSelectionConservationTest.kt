package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowStepFailure
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryImpactSelectionConservationTest {
    @Test
    fun `original grant rejects both conditioned filter branches`() {
        val fixture = ReviewedSelectionLedgerFixture()
        val transfers = listOf(fixture.filterInput(), fixture.filtered())
        val source = transfers.first().source
        val bytes =
            ValueFlowStep.detachedByteCount(source, fixture.base.domain, emptyList(), emptyList(), transfers).value
        println("Filter detached bytes: $bytes; original grant: ${fixture.base.domain.budget.returnedBytes.value}")
        assertTrue(bytes > fixture.base.domain.budget.returnedBytes.value)
        assertEquals(
            Refinement.Rejected(ValueFlowStepFailure.DETACHED_CAPACITY_EXCEEDED),
            ValueFlowStep.fromEvidence(
                source,
                emptyList(),
                emptyList(),
                ValueFlowTerminal.SupportedDomainExhausted,
                fixture.base.domain,
                RelationWorkCount.parse(1).componentValue(),
                transfers,
            ),
        )
        assertEquals(transfers, fixture.components.observe(source, transfers).reviewedTransfers)
    }

    @Test
    fun `filter ledger cannot omit callback input or unresolved predicate evidence`() {
        val route = SelectionRoute()
        assertEquals(Refinement.Rejected(QueryImpactLedgerFailure.MISSING_BRANCH), route.ledger(route.paths.take(1)))
        val unresolved = ValueFlowObligation(route.source, ValueFlowUnsupportedCause.UNMODELED_CALL)
        val observation = route.fixture.components.observe(route.source, route.transfers, listOf(unresolved))
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.MISSING_OBLIGATION),
            route.ledger(route.paths, observation),
        )
    }

    @Test
    fun `filter ledger preserves both branches and acceptance qualifications`() {
        val route = SelectionRoute()
        assertTrue(route.ledger(route.paths).componentValue().closure is QueryImpactClosure.Unresolved)
        assertEquals(2, route.transfers[1].target.conditions.facts.size)
        assertTrue(
            route.paths.all {
                it.classification == QueryImpactClassification.Established(QueryImpactRelationship.EXACT)
            }
        )
    }

    @Test
    fun `whole filter remains derived and nullable output keeps its premise`() {
        val fixture = ReviewedSelectionLedgerFixture()
        val filter = fixture.filtered(whole = true)
        val mapped = fixture.nonNullResult()
        val filterPath = selectionPath(filter, fixture.components.observe(filter.target))
        assertEquals(QueryImpactClassification.Established(QueryImpactRelationship.DERIVED), filterPath.classification)
        assertEquals(1, mapped.target.conditions.facts.size)
        val graph = QueryImpactRetainedGraph()
        assertTrue(mapped.storageBytes(graph) > 8L)
        assertEquals(8L, mapped.storageBytes(graph))
    }
}

private class SelectionRoute {
    val fixture = ReviewedSelectionLedgerFixture()
    val transfers = listOf(fixture.filterInput(), fixture.filtered())
    val source = transfers.first().source
    private val observed = fixture.components.observe(source, transfers)
    private val ends = transfers.map { fixture.components.observe(it.target) }
    val paths = transfers.zip(ends).map { (transfer, end) -> selectionPath(transfer, end) }

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

private fun selectionPath(transfer: ReviewedValueTransfer, terminal: ValueFlowStep) =
    QueryImpactPath.fromEvidence(
            transfer.source,
            listOf(QueryImpactStep.ReviewedLibrary(transfer)),
            QueryImpactRepresentation.NotModeled,
            QueryImpactTerminal.SupportedDomainEnd.admit(terminal).componentValue(),
        )
        .componentValue()
