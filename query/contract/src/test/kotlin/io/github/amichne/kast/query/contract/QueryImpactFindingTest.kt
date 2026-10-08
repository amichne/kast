package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class QueryImpactFindingTest {
    @Test
    fun `finding view preserves original identities for distinct routes to the same destination`() {
        val fixture = QueryImpactLedgerFixture()
        val ledger = fixture.ledger(fixture.paths).value()
        val view = QueryImpactWitnessView.create(ledger, QueryImpactWitnessSection.FINDINGS, 0, 2).value()
        val findings = view.entries.map { (it.evidence as QueryImpactWitnessEntry.Finding).finding }

        assertEquals(2, view.sectionCount.value)
        assertEquals(listOf(0, 1), findings.map { it.pathOrdinal.value })
        assertSame(ledger.paths[0], findings[0].path)
        assertSame(ledger.paths[1], findings[1].path)
        assertEquals(findings[0].path.producer, findings[1].path.producer)
        assertEquals(findings[0].path.destination, findings[1].path.destination)
        assertNotEquals(findings[0].path.steps, findings[1].path.steps)
        assertSame(ledger, view.ledger)
    }

    @Test
    fun `selected finding ordinal remains the original path ordinal`() {
        val fixture = QueryImpactLedgerFixture()
        val ledger = fixture.ledger(fixture.paths).value()
        val view = QueryImpactWitnessView.create(ledger, QueryImpactWitnessSection.FINDINGS, 1, 2).value()
        val record = view.entries.single()
        val finding = (record.evidence as QueryImpactWitnessEntry.Finding).finding

        assertEquals(1, record.ordinal.value)
        assertEquals(1, finding.pathOrdinal.value)
        assertSame(ledger.paths[1], finding.path)
        assertSame(ledger.closure, view.ledger.closure)
    }

    @Test
    fun `finding construction refuses an ordinal outside the original ledger`() {
        val fixture = QueryImpactLedgerFixture()
        val ledger = fixture.ledger(fixture.paths).value()

        assertEquals(
            Refinement.Rejected(QueryImpactWitnessFailure.INVALID_RANGE),
            QueryImpactFinding.fromOriginalPath(ledger, -1),
        )
        assertEquals(
            Refinement.Rejected(QueryImpactWitnessFailure.INVALID_RANGE),
            QueryImpactFinding.fromOriginalPath(ledger, 2),
        )
    }
}

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
