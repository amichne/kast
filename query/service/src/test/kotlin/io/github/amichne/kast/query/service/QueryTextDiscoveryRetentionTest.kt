package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.query.contract.QuerySymbolFields
import io.github.amichne.kast.query.contract.QueryTextMatches
import io.github.amichne.kast.symbol.contract.ResolvedSymbol
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWord
import io.github.amichne.kast.symbol.contract.SymbolResolutionResult
import io.github.amichne.kast.symbol.contract.SymbolTextMatch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryTextDiscoveryRetentionTest {
    private val text = QueryTextDiscoveryFixture()

    @Test
    fun `retained word rows keep immutable lexical evidence without repeating discovery or refinement`() = runTest {
        val fixture = QueryServiceTest()
        val match = text.match(fixture)
        var discoveries = 0
        var refinements = 0
        val service =
            fixture.service(
                discovery = text.discovery(match) { discoveries++ },
                exact =
                    fixture.exactOperations {
                        refinements++
                        SymbolResolutionResult.Resolved(ResolvedSymbol(fixture.selector(it)))
                    },
            )
        val request = fixture.request(text.plan(), 8)
        val prefix = service.run(request)
        val retained = QueryRetainedResult.capture(request.lease, prefix).refined() as QueryRetainedResult.Symbols
        val suffixPlan = text.admit(QuerySourceSyntax.Retained(retained))
        val suffix =
            assertInstanceOf(QueryExecutionResult.Complete::class.java, service.run(fixture.request(suffixPlan, 8)))
        val matches = suffix.result.symbolRows().single().textMatches.values

        assertEquals(listOf(match), matches)
        assertEquals(1, discoveries)
        assertEquals(1, refinements)
        assertThrows(UnsupportedOperationException::class.java) { (matches as MutableList).clear() }
        val mutable = mutableListOf(match)
        val detached = QueryTextMatches.from(mutable).refined()
        mutable.clear()
        assertEquals(listOf(match), detached.values)
    }

    @Test
    fun `lexical evidence contributes to output and retained byte authority`() = runTest {
        val fixture = QueryServiceTest()
        val match = text.match(fixture)
        val service =
            fixture.service(
                discovery = text.discovery(match),
                exact =
                    fixture.exactOperations { SymbolResolutionResult.Resolved(ResolvedSymbol(fixture.selector(it))) },
            )
        val request = fixture.request(text.plan(), 8)
        val result = assertInstanceOf(QueryExecutionResult.Complete::class.java, service.run(request))
        val row = result.result.symbolRows().single()
        val bare = row.copy(textMatches = QueryTextMatches.Empty)
        assertTrue(row.projectedUtf8Size() > bare.projectedUtf8Size())
        val bareResult =
            result.copy(
                result =
                    result.result.copy(rows = io.github.amichne.kast.query.contract.QueryRows.Symbols.of(listOf(bare)))
            )
        val retained = QueryRetainedResult.capture(request.lease, result).refined()
        val bareRetained = QueryRetainedResult.capture(request.lease, bareResult).refined()
        assertTrue(retained.retainedBytes > bareRetained.retainedBytes)
    }

    @Test
    fun `distinct retains first arrival and later lexical proof for the same exact owner`() = runTest {
        val fixture = QueryServiceTest()
        val first = text.match(fixture)
        val second =
            SymbolTextMatch.fromBoundary(
                    SymbolDiscoveryWord.parse("bootstrap").refined(),
                    first.lease,
                    first.file,
                    12,
                    21,
                    7,
                    27,
                    "bootstrap",
                    12,
                    1,
                )
                .refined()
        val service =
            fixture.service(
                discovery = text.discovery(first),
                exact =
                    fixture.exactOperations { SymbolResolutionResult.Resolved(ResolvedSymbol(fixture.selector(it))) },
            )
        val result =
            assertInstanceOf(QueryExecutionResult.Complete::class.java, service.run(fixture.request(text.plan(), 8)))
        val firstRow = result.result.symbolRows().single()
        val secondRow = firstRow.copy(textMatches = QueryTextMatches.singleton(second))
        val stage =
            ExactQueryStage.Distinct(
                ExactQueryStage.Emit(QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refined()))
            )
        val rows = QueryIdentityRows(emptyMap())
        rows.acceptDistinct(stage, firstRow).refined()
        rows.acceptDistinct(stage, secondRow).refined()
        val restored = QueryIdentityRows(rows.snapshot())
        val distinct = restored.flushDistinct(stage).single()
        assertEquals(firstRow.arrival, distinct.arrival)
        assertEquals(firstRow.connections, distinct.connections)
        assertEquals(listOf(first, second), distinct.textMatches.values)
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = (this as Refinement.Refined).value
}
