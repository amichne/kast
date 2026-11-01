package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ReviewedValueRelation
import io.github.amichne.kast.relation.contract.ValueCondition
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryImpactMappedConservationTest {
    @Test
    fun `omitted mapped observation and unresolved sibling reject complete ledger evidence`() {
        val route = MappedRoute()
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.UNPROVEN_REVIEWED_STEP),
            route.ledger(
                listOf(route.path),
                listOf(route.fixture.base.observe(route.edge.source)) + route.observations.drop(1),
            ),
        )
        val obligation = ValueFlowObligation(route.edge.source, ValueFlowUnsupportedCause.UNMODELED_CALL)
        val observations =
            listOf(route.fixture.base.observe(route.edge.source, listOf(route.edge), listOf(obligation))) +
                route.observations.drop(1)
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.MISSING_OBLIGATION),
            route.ledger(listOf(route.path), observations),
        )
        val unresolved =
            QueryImpactPath.fromEvidence(
                    route.edge.source,
                    emptyList(),
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.Unresolved.Flow(obligation),
                )
                .componentValue()
        assertTrue(
            route.ledger(listOf(route.path, unresolved), observations).componentValue().closure
                is QueryImpactClosure.Unresolved
        )
    }

    @Test
    fun `mapped route keeps its nested path condition and reviewed classification`() {
        val route = MappedRoute()
        val ledger = route.ledger(listOf(route.path), route.observations).componentValue()
        assertEquals(QueryImpactClassification.Established(QueryImpactRelationship.EXACT), route.path.classification)
        assertTrue(ledger.closure is QueryImpactClosure.Unresolved)
        assertEquals(route.edge.source.valuePath.segments, route.edge.target.valuePath.segments.drop(1))
        val entry = (route.edge.relation as ReviewedValueRelation.MappedValue).entry
        assertEquals(
            entry,
            (route.edge.target.conditions.facts.single() as ValueCondition.ReviewedMappedKeySurvives).entry,
        )
        val graph = QueryImpactRetainedGraph()
        assertTrue(route.edge.storageBytes(graph) > 8L)
        assertEquals(8L, route.edge.storageBytes(graph))
        assertEquals(8L, entry.storageBytes(graph))
    }
}

private class MappedRoute {
    val fixture = ReviewedMappedLedgerFixture()
    val edge = fixture.transfer
    val observations = listOf(fixture.base.observe(edge.source, listOf(edge)), fixture.base.observe(edge.target))
    val path =
        QueryImpactPath.fromEvidence(
                edge.source,
                listOf(QueryImpactStep.ReviewedLibrary(edge)),
                QueryImpactRepresentation.NotModeled,
                QueryImpactTerminal.SupportedDomainEnd.admit(observations.last()).componentValue(),
            )
            .componentValue()

    fun ledger(paths: List<QueryImpactPath>, observations: List<ValueFlowStep>) =
        QueryImpactLedger.fromEvidence(
            listOf(edge.source),
            fixture.base.domain.boundary,
            QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
            emptyList(),
            emptyList(),
            observations,
            paths,
        )
}
