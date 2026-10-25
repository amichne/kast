package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.Collections
import java.util.IdentityHashMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryImpactRowsReuseTest {
    @Test
    fun `output pages reuse the immutable paths admitted by the ledger`() {
        val fixture = QueryImpactLedgerFixture()
        val ledger = fixture.ledger(fixture.paths).value()
        val inventories = Collections.newSetFromMap(IdentityHashMap<List<QueryImpactPath>, Boolean>())
        inventories += ledger.paths
        repeat(128) {
            val rows = QueryRows.ValuePaths.fromInvestigation(ledger).value()
            inventories += rows.values
            assertEquals(fixture.paths, rows.values)
            assertSame(ledger, (rows.accounting as QueryValuePathAccounting.Investigated).ledger)
        }
        assertEquals(1, inventories.size, "Output pages must not copy the admitted path inventory")
        assertThrows(UnsupportedOperationException::class.java) {
            (ledger.paths as MutableList<QueryImpactPath>).clear()
        }
        assertEquals(fixture.paths, ledger.paths)
    }

    @Test
    fun `duplicate paths reject before an investigation can produce rows`() {
        val fixture = QueryImpactLedgerFixture()
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.DUPLICATE_PATH),
            fixture.ledger(fixture.paths + fixture.paths.first()),
        )
    }
}

private fun <T, F> Refinement<T, F>.value(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
