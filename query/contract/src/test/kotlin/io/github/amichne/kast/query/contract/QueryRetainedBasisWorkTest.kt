package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter

class QueryRetainedBasisWorkTest {
    @Test
    fun `one hundred twenty eight captures keep one ledger scan and the latest selected result`(
        reporter: TestReporter
    ) {
        val fixture = QueryImpactLedgerFixture()
        val rows = QueryRows.ValuePaths.fromInvestigation(fixture.ledger(fixture.paths).value()).value()
        val events = mutableListOf<QueryRetainedBasisEvent>()
        val observation = QueryRetainedBasisObservation(events::add)
        var capture: QueryRetainedCapture = QueryRetainedCapture.Initial
        repeat(128) { index ->
            val next = capture.capture(fixture.lease, page(rows, index % fixture.paths.size), observation).value()
            assertSame(
                fixture.paths[index % fixture.paths.size],
                (next.result as QueryRetainedResult.ValuePaths).valuePaths.single(),
            )
            capture = next
        }

        val scans = events.count { it == QueryRetainedBasisEvent.LEDGER_CHECK_STARTED }
        reporter.publishEntry("ledger-basis-scans", scans.toString())
        assertEquals(1, scans)
        assertEquals(127, events.count { it == QueryRetainedBasisEvent.LEDGER_PROOF_REUSED })
    }

    @Test
    fun `successive pages examine their immutable ledger basis once`(reporter: TestReporter) {
        val fixture = QueryImpactLedgerFixture()
        val ledger = fixture.ledger(fixture.paths).value()
        val rows = QueryRows.ValuePaths.fromInvestigation(ledger).value()
        val events = mutableListOf<QueryRetainedBasisEvent>()
        val observation = QueryRetainedBasisObservation(events::add)
        val first = QueryRetainedCapture.Initial.capture(fixture.lease, page(rows, 0), observation).value()
        val second = first.capture(fixture.lease, page(rows, 1), observation).value()

        assertSame(fixture.paths[0], (first.result as QueryRetainedResult.ValuePaths).valuePaths.single())
        assertSame(fixture.paths[1], (second.result as QueryRetainedResult.ValuePaths).valuePaths.single())
        val scans = events.count { it == QueryRetainedBasisEvent.LEDGER_CHECK_STARTED }
        reporter.publishEntry("ledger-basis-scans", scans.toString())
        assertEquals(1, scans)
    }

    private fun page(rows: QueryRows.ValuePaths, index: Int): QueryExecutionResult =
        QueryExecutionResult.Qualified(
            QueryResult(rows.selectRows(listOf(index)).value(), emptyList()),
            QueryCoverage.Qualified.create(QueryCount.parse(1).value(), setOf(QueryLimitation.RELATION_INCOMPLETE))
                .value(),
        )
}

private fun <T, F> Refinement<T, F>.value(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Basis fixture rejected: $failure")
    }
