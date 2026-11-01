package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ReviewedValueRelation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryImpactDistinctConservationTest {
    @Test
    fun `conditional observation cannot authorize a distinct entry`() {
        val f = ReviewedDistinctLedgerFixture()
        val end = f.base.observe(f.distinct.target)
        val path =
            QueryImpactPath.fromEvidence(
                    f.source,
                    listOf(QueryImpactStep.ReviewedLibrary(f.distinct)),
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.SupportedDomainEnd.admit(end).componentValue(),
                )
                .componentValue()
        fun ledger(distinct: Boolean) =
            QueryImpactLedger.fromEvidence(
                listOf(f.source),
                f.base.domain.boundary,
                QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
                emptyList(),
                emptyList(),
                listOf(f.base.observe(f.source, listOf(if (distinct) f.distinct else f.conditional)), end),
                listOf(path),
            )
        assertEquals(Refinement.Rejected(QueryImpactLedgerFailure.UNPROVEN_REVIEWED_STEP), ledger(false))
        val accepted = ledger(true).componentValue()
        assertTrue(accepted.closure is QueryImpactClosure.Unresolved)
        assertEquals(QueryImpactClassification.Established(QueryImpactRelationship.EXACT), path.classification)
        assertTrue(f.distinct.target.conditions.facts.isEmpty())
        assertNotEquals(f.conditional.relation, f.distinct.relation)
    }

    @Test
    fun `distinct storage keeps the complete proof and charges longer string constants`() {
        val small = ReviewedDistinctLedgerFixture("a")
        val large = ReviewedDistinctLedgerFixture("a".repeat(80))
        val smallBytes = small.proof.storageBytes(QueryImpactRetainedGraph())
        val largeBytes = large.proof.storageBytes(QueryImpactRetainedGraph())
        assertEquals(3L * 79, largeBytes - smallBytes)
        val allowance = QueryStorageAllowance.Bounded(QueryByteLimit.parse(smallBytes).componentValue())
        assertTrue(allowance.permits(smallBytes))
        assertTrue(!allowance.permits(largeBytes))
        val graph = QueryImpactRetainedGraph()
        assertTrue(small.distinct.storageBytes(graph) > 8L)
        assertEquals(8L, small.proof.storageBytes(graph))
        assertEquals(8L, small.input.storageBytes(graph))
        val relation = small.distinct.relation as ReviewedValueRelation.AssociationComponent
        assertEquals(8L, relation.entry.keyEvidence.storageBytes(graph))
    }
}
