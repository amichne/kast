package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ReviewedValueRelation
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowStepFailure
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryImpactComponentConservationTest {
    @Test
    fun `omitted tuple observation and unresolved siblings cannot become complete entry paths`() {
        val f = ComponentRoute()
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.UNPROVEN_REVIEWED_STEP),
            f.ledger(listOf(f.path), listOf(f.fixture.observe(f.tuple.source)) + f.observations.drop(1)),
        )
        val obligation = ValueFlowObligation(f.tuple.source, ValueFlowUnsupportedCause.UNMODELED_CALL)
        val observations =
            listOf(f.fixture.observe(f.tuple.source, listOf(f.tuple), listOf(obligation))) + f.observations.drop(1)
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.MISSING_OBLIGATION),
            f.ledger(listOf(f.path), observations),
        )
        val unresolved =
            QueryImpactPath.fromEvidence(
                    f.tuple.source,
                    emptyList(),
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.Unresolved.Flow(obligation),
                )
                .componentValue()
        assertTrue(
            f.ledger(listOf(f.path, unresolved), observations).componentValue().closure is QueryImpactClosure.Unresolved
        )
    }

    @Test
    fun `entry route keeps compiler return reviewed provenance and the unique key premise`() {
        val f = ComponentRoute()
        val ledger = f.ledger(listOf(f.path), f.observations).componentValue()
        println("Component route detached bytes: " + f.observations.map { it.retainedBytes })
        assertTrue(f.observations.all { it.retainedBytes in 1..f.fixture.domain.budget.returnedBytes.value })
        assertEquals(QueryImpactClassification.Established(QueryImpactRelationship.EXACT), f.path.classification)
        assertTrue(ledger.closure is QueryImpactClosure.Unresolved)
        val size = QueryImpactWitnessView.count(ledger, QueryImpactWitnessSection.NATIVE_READS).componentValue()
        val entries =
            QueryImpactWitnessView.create(ledger, QueryImpactWitnessSection.NATIVE_READS, 0, size.value)
                .componentValue()
                .entries
        assertEquals(2, entries.count { it.evidence is QueryImpactWitnessEntry.ReviewedTransfer })
        assertEquals(1, entries.count { it.evidence is QueryImpactWitnessEntry.CompilerTransfer })
        assertEquals(1, f.entry.target.conditions.facts.size)
        assertEquals(f.tuple.source.valuePath.segments, f.entry.target.valuePath.segments.drop(1))
    }

    @Test
    fun `old detached grant rejects the larger tuple proof without losing its capacity failure`() {
        val f = ReviewedComponentLedgerFixture()
        val tuple = f.construct()
        val bytes =
            ValueFlowStep.detachedByteCount(
                    tuple.source,
                    f.base.domain,
                    emptyList(),
                    emptyList(),
                    listOf(tuple),
                )
                .value
        println("Tuple detached bytes: $bytes; original grant: ${f.base.domain.budget.returnedBytes.value}")
        assertTrue(bytes > f.base.domain.budget.returnedBytes.value)
        assertEquals(
            Refinement.Rejected(ValueFlowStepFailure.DETACHED_CAPACITY_EXCEEDED),
            ValueFlowStep.fromEvidence(
                tuple.source,
                emptyList(),
                emptyList(),
                ValueFlowTerminal.SupportedDomainExhausted,
                f.base.domain,
                RelationWorkCount.parse(1).componentValue(),
                listOf(tuple),
            ),
        )
        assertEquals(tuple, f.observe(tuple.source, listOf(tuple)).reviewedTransfers.single())
    }

    @Test
    fun `storage charges tuple origin correlation and helper evidence once per retained identity`() {
        val f = ComponentRoute()
        val graph = QueryImpactRetainedGraph()
        assertTrue(f.entry.storageBytes(graph) > 8L)
        assertEquals(8L, f.entry.storageBytes(graph))
        val entry = (f.entry.relation as ReviewedValueRelation.AssociationComponent).entry
        assertEquals(8L, entry.storageBytes(graph))
        assertEquals(8L, entry.tupleOrigin.storageBytes(graph))
        assertEquals(8L, entry.binding.artifact.storageBytes(graph))
        val other = f.fixture.associated(f.fixture.construct(start = 55))
        assertNotEquals(f.entry.relation, other.relation)
        assertTrue(other.storageBytes(graph) > 8L)
    }
}

private class ComponentRoute {
    val fixture = ReviewedComponentLedgerFixture()
    val tuple = fixture.construct()
    private val returned = fixture.returned(tuple)
    val entry = fixture.associated(tuple)
    val observations =
        listOf(
            fixture.observe(tuple.source, listOf(tuple)),
            ValueFlowStep.fromEvidence(
                    returned.source,
                    listOf(returned),
                    emptyList(),
                    ValueFlowTerminal.SupportedDomainExhausted,
                    fixture.domain,
                    RelationWorkCount.parse(1).componentValue(),
                    reviewedTransfers = emptyList(),
                )
                .componentValue(),
            fixture.observe(entry.source, listOf(entry)),
            fixture.observe(entry.target),
        )
    val path =
        QueryImpactPath.fromEvidence(
                tuple.source,
                listOf(
                    QueryImpactStep.ReviewedLibrary(tuple),
                    QueryImpactStep.Compiler(returned),
                    QueryImpactStep.ReviewedLibrary(entry),
                ),
                QueryImpactRepresentation.NotModeled,
                QueryImpactTerminal.SupportedDomainEnd.admit(observations.last()).componentValue(),
            )
            .componentValue()

    fun ledger(paths: List<QueryImpactPath>, observations: List<ValueFlowStep>) =
        QueryImpactLedger.fromEvidence(
            listOf(tuple.source),
            fixture.domain.boundary,
            QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
            emptyList(),
            emptyList(),
            observations,
            paths,
        )
}
