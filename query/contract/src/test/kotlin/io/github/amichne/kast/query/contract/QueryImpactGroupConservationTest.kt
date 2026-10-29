package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryImpactGroupConservationTest {
    @Test
    fun `group ledger rejects a missing seed path and unresolved siblings`() {
        val fixture = ReviewedGroupLedgerFixture()
        val transfer = fixture.original()
        val end = fixture.components.observe(transfer.target)
        val observed = fixture.components.observe(transfer.source, listOf(transfer))
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.MISSING_SEED_PATH),
            groupLedger(fixture, transfer, observed, end, emptyList()),
        )
        val unresolved =
            fixture.components.observe(
                transfer.source,
                listOf(transfer),
                listOf(ValueFlowObligation(transfer.source, ValueFlowUnsupportedCause.UNMODELED_CALL)),
            )
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.MISSING_OBLIGATION),
            groupLedger(fixture, transfer, unresolved, end, listOf(groupPath(transfer, end))),
        )
    }

    @Test
    fun `group ledger keeps normal membership and actual transformed return`() {
        val fixture = ReviewedGroupLedgerFixture()
        for (transfer in listOf(fixture.original(), fixture.transformed())) {
            val end = fixture.components.observe(transfer.target)
            val path = groupPath(transfer, end)
            val ledger =
                groupLedger(
                        fixture,
                        transfer,
                        fixture.components.observe(transfer.source, listOf(transfer)),
                        end,
                        listOf(path),
                    )
                    .componentValue()
            assertTrue(ledger.closure is QueryImpactClosure.Unresolved)
            assertEquals(QueryImpactClassification.Established(QueryImpactRelationship.EXACT), path.classification)
            assertEquals(1, transfer.target.conditions.facts.size)
        }
    }

    @Test
    fun `whole content and representative keys remain derived with stored proof`() {
        val fixture = ReviewedGroupLedgerFixture()
        for (transfer in listOf(fixture.original(whole = true), fixture.key())) {
            assertEquals(
                QueryImpactClassification.Established(QueryImpactRelationship.DERIVED),
                groupPath(transfer, fixture.components.observe(transfer.target)).classification,
            )
            val graph = QueryImpactRetainedGraph()
            assertTrue(transfer.storageBytes(graph) > 8L)
            assertEquals(8L, transfer.storageBytes(graph))
        }
    }
}

private fun groupPath(transfer: ReviewedValueTransfer, end: ValueFlowStep) =
    QueryImpactPath.fromEvidence(
            transfer.source,
            listOf(QueryImpactStep.ReviewedLibrary(transfer)),
            QueryImpactRepresentation.NotModeled,
            QueryImpactTerminal.SupportedDomainEnd.admit(end).componentValue(),
        )
        .componentValue()

private fun groupLedger(
    fixture: ReviewedGroupLedgerFixture,
    transfer: ReviewedValueTransfer,
    observed: ValueFlowStep,
    end: ValueFlowStep,
    paths: List<QueryImpactPath>,
) =
    QueryImpactLedger.fromEvidence(
        listOf(transfer.source),
        fixture.components.domain.boundary,
        QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
        emptyList(),
        emptyList(),
        listOf(observed, end),
        paths,
    )
