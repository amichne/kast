package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ReviewedCallValueBinding
import io.github.amichne.kast.relation.contract.ReviewedEntryReadOrder
import io.github.amichne.kast.relation.contract.ReviewedValueRelation
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryImpactEntryConservationTest {
    @Test
    fun `entry ledger rejects an omitted key branch or unresolved sibling`() {
        val route = EntryRoute()
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.MISSING_BRANCH),
            route.ledger(route.paths.take(1), route.observations),
        )
        val unresolved = ValueFlowObligation(route.input.source, ValueFlowUnsupportedCause.UNMODELED_CALL)
        val first = route.fixture.base.observe(route.input.source, listOf(route.input, route.key), listOf(unresolved))
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.MISSING_OBLIGATION),
            route.ledger(route.paths, listOf(first) + route.observations.drop(1)),
        )
    }

    @Test
    fun `entry ledger preserves both reviewed branches and conditional completion`() {
        val route = EntryRoute()
        val ledger = route.ledger(route.paths, route.observations).componentValue()
        assertTrue(ledger.closure is QueryImpactClosure.Unresolved)
        assertTrue(
            route.paths.all {
                it.classification == QueryImpactClassification.Established(QueryImpactRelationship.EXACT)
            }
        )
        assertEquals(route.input.source.conditions, route.input.target.conditions)
        assertEquals(route.key.source.conditions.facts.size + 1, route.key.target.conditions.facts.size)
    }

    @Test
    fun `destructuring storage keeps the exact declaration range`() {
        val transfer = ReviewedEntryLedgerFixture().destructuredSecond()
        val binding = transfer.binding as ReviewedCallValueBinding
        val order = binding.entryReadOrder as ReviewedEntryReadOrder.Destructured
        assertEquals(componentRange(120, 145), order.declarationRange)
        val graph = QueryImpactRetainedGraph()
        assertTrue(order.storageBytes(graph) > 8L)
        assertEquals(8L, graph.node(order.declarationRange) { error("Range was not charged") })
        assertEquals(8L, order.storageBytes(graph))
    }

    @Test
    fun `entry storage charges shared family and metadata once per identity`() {
        val fixture = ReviewedEntryLedgerFixture()
        val input = fixture.input(false)
        val graph = QueryImpactRetainedGraph()
        assertTrue(input.storageBytes(graph) > 8L)
        assertEquals(8L, input.storageBytes(graph))
        val entry = (input.relation as ReviewedValueRelation.EntryInput).entry
        assertEquals(8L, entry.family.storageBytes(graph))
        val read = fixture.read(false)
        assertTrue(read.storageBytes(graph) > 8L)
        assertEquals(8L, read.binding.artifact.storageBytes(graph))
    }
}

private class EntryRoute {
    val fixture = ReviewedEntryLedgerFixture()
    val input = fixture.input(true)
    val key = fixture.preserved()
    val observations =
        listOf(
            fixture.base.observe(input.source, listOf(input, key)),
            fixture.base.observe(input.target),
            fixture.base.observe(key.target),
        )
    val paths = listOf(path(input, observations[1]), path(key, observations[2]))

    private fun path(edge: ReviewedValueTransfer, terminal: ValueFlowStep) =
        QueryImpactPath.fromEvidence(
                edge.source,
                listOf(QueryImpactStep.ReviewedLibrary(edge)),
                QueryImpactRepresentation.NotModeled,
                QueryImpactTerminal.SupportedDomainEnd.admit(terminal).componentValue(),
            )
            .componentValue()

    fun ledger(paths: List<QueryImpactPath>, observations: List<ValueFlowStep>) =
        QueryImpactLedger.fromEvidence(
            listOf(input.source),
            fixture.base.domain.boundary,
            QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
            emptyList(),
            emptyList(),
            observations,
            paths,
        )
}
