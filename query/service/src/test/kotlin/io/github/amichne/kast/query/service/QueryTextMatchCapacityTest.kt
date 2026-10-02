package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionRejection
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryJoinMode
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QuerySymbolFields
import io.github.amichne.kast.query.contract.QueryTextMatchFailure
import io.github.amichne.kast.query.contract.QueryTextMatches
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWord
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolTextMatch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class QueryTextMatchCapacityTest {
    @Test
    fun `match boundary rejects the first exemplar beyond the wire capacity`() {
        val fixture = QueryServiceTest()
        val selector = fixture.selector(fixture.selection())
        val rejection =
            assertInstanceOf(Refinement.Rejected::class.java, QueryTextMatches.from(matches(selector, 1001)))
        assertEquals(QueryTextMatchFailure.ITEM_LIMIT_EXCEEDED, rejection.failure)
    }

    @Test
    fun `maximum evidence count remains projection compatible and deduplicates exact exemplars`() {
        val fixture = QueryServiceTest()
        val selector = fixture.selector(fixture.selection())
        val expected = matches(selector, 1000)
        val admitted = QueryTextMatches.from(expected + expected).refined()
        assertEquals(1000, QueryTextMatches.MAX_ITEMS)
        assertEquals(expected, admitted.values)
        assertEquals(admitted, admitted.merge(QueryTextMatches.singleton(expected.first())).refined())
        assertEquals(admitted, admitted.merge(QueryTextMatches.Empty).refined())
    }

    @Test
    fun `failed evidence merge protects both immutable inputs`() {
        val fixture = QueryServiceTest()
        val first = row(fixture, 0, 1000).textMatches
        val second = row(fixture, 1000, 1).textMatches
        assertEquals(Refinement.Rejected(QueryTextMatchFailure.ITEM_LIMIT_EXCEEDED), first.merge(second))
        assertEquals(1000, first.values.size)
        assertEquals(1, second.values.size)
    }

    @Test
    fun `distinct capacity rejection preserves the prior lexical evidence`() = runTest {
        val fixture = QueryServiceTest()
        val first = row(fixture, 0, 1000)
        val second = row(fixture, 1000, 1)
        assertRejected(fixture, listOf(first, second), listOf(QueryStepSyntax.Distinct))
        val stage =
            ExactQueryStage.Distinct(
                ExactQueryStage.Emit(QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refined()))
            )
        val grouping = QueryIdentityRows(emptyMap())
        grouping.acceptDistinct(stage, first).refined()
        assertEquals(
            Refinement.Rejected(QueryIdentityRowFailure.TEXT_MATCH_LIMIT_EXCEEDED),
            grouping.acceptDistinct(stage, second),
        )
        assertEquals(listOf(first), QueryIdentityRows(grouping.snapshot()).flushDistinct(stage))
        assertEquals(1000, first.textMatches.values.size)
        assertEquals(1, second.textMatches.values.size)
    }

    @Test
    fun `union capacity rejection remains a finite execution reason`() = runTest {
        val fixture = QueryServiceTest()
        assertRejected(
            fixture,
            listOf(row(fixture, 0, 1000)),
            listOf(QueryStepSyntax.Union(retained(row(fixture, 1000, 1)))),
        )
    }

    @Test
    fun `intersection capacity rejection remains a finite execution reason`() = runTest {
        val fixture = QueryServiceTest()
        assertRejected(
            fixture,
            listOf(row(fixture, 0, 1000)),
            listOf(QueryStepSyntax.Intersect(retained(row(fixture, 1000, 1)))),
        )
    }

    @Test
    fun `right set grouping capacity rejection remains a finite execution reason`() = runTest {
        val fixture = QueryServiceTest()
        val right = retained(row(fixture, 0, 1000), row(fixture, 1000, 1))
        assertRejected(fixture, listOf(row(fixture, 0, 1)), listOf(QueryStepSyntax.Intersect(right)))
    }

    @Test
    fun `semi join capacity rejection remains a finite execution reason`() = runTest {
        val fixture = QueryServiceTest()
        val right = retained(row(fixture, 1000, 1))
        assertRejected(fixture, listOf(row(fixture, 0, 1000)), listOf(QueryStepSyntax.Join(QueryJoinMode.Semi, right)))
    }

    private suspend fun assertRejected(
        fixture: QueryServiceTest,
        rows: List<QuerySymbol>,
        steps: List<QueryStepSyntax>,
    ) {
        val plan =
            fixture.admittedPlan(
                QuerySourceSyntax.Retained(retained(*rows.toTypedArray())),
                steps,
                QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refined()),
            )
        val rejection =
            assertInstanceOf(
                QueryExecutionResult.Rejected::class.java,
                fixture.service().run(fixture.request(plan, 64)),
            )
        assertEquals(QueryExecutionRejection.TEXT_MATCH_LIMIT_EXCEEDED, rejection.reason)
        assertEquals(
            rows.map { it.textMatches },
            ((plan as io.github.amichne.kast.query.contract.AdmittedQueryPlan.Retained).source
                    as QueryRetainedResult.Symbols)
                .symbols
                .map { it.textMatches },
        )
    }

    private fun row(fixture: QueryServiceTest, first: Int, count: Int): QuerySymbol {
        val selector = fixture.selector(fixture.selection())
        return QuerySymbol(
            SymbolDescription.from(selector),
            emptyList(),
            textMatches = QueryTextMatches.from(matches(selector, count, first)).refined(),
        )
    }

    private fun matches(selector: SymbolSelector, count: Int, first: Int = 0): List<SymbolTextMatch> {
        val file: CanonicalWorkspaceFilePath = (selector.file as SymbolDiscoveryFileIdentity.Workspace).path
        return List(count) { index ->
            val word = SymbolDiscoveryWord.parse("w${first + index}").refined()
            SymbolTextMatch.fromBoundary(
                    word,
                    selector.lease,
                    file,
                    12,
                    12 + word.value.length,
                    7,
                    27,
                    word.value,
                    12,
                    1,
                )
                .refined()
        }
    }

    private fun retained(vararg rows: QuerySymbol): QueryRetainedResult.Symbols =
        QueryRetainedResult.capture(
                rows.first().selector.lease,
                QueryExecutionResult.Complete(
                    QueryResult(QueryRows.Symbols.of(rows.toList()), emptyList()),
                    QueryCoverage.Complete(QueryCount.parse(rows.size).refined()),
                ),
            )
            .refined() as QueryRetainedResult.Symbols

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Expected refinement, got $failure")
        }
}
