package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.MAX_PROTOCOL_ITEMS
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRetentionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.QueryTextMatchDocument
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QueryTextMatches
import io.github.amichne.kast.symbol.contract.CanonicalSymbolId
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWord
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolTextMatch
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryTextMatchProjectionTest {
    @Test
    fun `domain match capacity fits the canonical wire evidence collection`() {
        assertEquals(MAX_PROTOCOL_ITEMS, QueryTextMatches.MAX_ITEMS)
    }

    @Test
    fun `text result read retains lexical evidence and exact ref without another semantic execution`() = runTest {
        val fixture = RelationPagingFixture.published()
        val lease = fixture.authority.requirePublished().refined()
        val row = matchedRow(fixture, lease)
        val output = QueryOutputDocument.Symbols(bounded(emptyList<QuerySymbolFieldDocument>()))
        var executions = 0
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { admitted ->
                    assertInstanceOf(AdmittedQueryPlan.Text::class.java, admitted.plan)
                    executions++
                    check(executions == 1) { "Retained presentation must not execute semantic discovery" }
                    QueryExecutionResult.Complete.create(
                        QueryResult(QueryRows.Symbols.of(listOf(row)), emptyList()),
                        QueryCoverage.Complete(QueryCount.parse(1).refined()),
                    )
                },
                fixture.references,
            )
        val first =
            protocol.execute(retainedTextRequest(output), fixture.authority, budget()) as OperationOutcome.Complete
        val reference = (first.evidence.payload.retention as QueryResultRetention.Retained).reference
        val read =
            protocol.execute(
                QueryRunRequest.ReadResult.symbols(reference, output = output),
                fixture.authority,
                budget(),
            ) as OperationOutcome.Complete
        val original = first.evidence.payload.items.values.single() as QueryResultItemDocument.ExactSymbol
        val restored = read.evidence.payload.items.values.single() as QueryResultItemDocument.ExactSymbol
        assertEquals(original, restored)
        assertSameSelector(
            fixture.selector,
            (fixture.references.restoreExact(restored.ref.token, lease) as CanonicalSelectorDecoding.Decoded).value,
        )
        assertEquals("host", (restored.matches!!.values.single() as QueryTextMatchDocument.IndexedWord).word.value)
        assertEquals(1, executions)
    }

    private fun retainedTextRequest(output: QueryOutputDocument.Symbols) =
        QueryRunRequest.Run(
            QueryFromDocument.TextWord(
                ProtocolText.parse("host").refined(),
                QueryScopeDocument(bounded(listOf(ProtocolText.parse("main").refined())), null, null),
                bounded(listOf(QueryDeclarationKindDocument.FUNCTION)),
            ),
            bounded(emptyList()),
            output,
            QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
            retention = QueryRetentionModeDocument.RETAIN,
            completion = io.github.amichne.kast.protocol.contract.QueryCompletionPolicyDocument.Progressive,
        )

    private fun budget() =
        QueryBudget(
            ResourceBudget(
                ResultLimit.parse(10).refined(),
                WorkUnitLimit.parse(100).refined(),
                ElapsedTimeLimitMillis.parse(1000).refined(),
            ),
            QueryByteLimit.parse(10000).refined(),
        )

    @Test
    fun `lexical match projects beside a reusable exact ref and never becomes occurrence evidence`() {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
        val lease = SemanticReadLease(root, EvidenceGeneration.parse(7).refined())
        val fixture = RelationPagingFixture(lease)
        val row = matchedRow(fixture, lease)
        val output =
            QueryOutputDocument.Symbols(BoundedProtocolList.create(emptyList<QuerySymbolFieldDocument>()).refined())
        val projector = QueryItemProjector(fixture.references)
        val projected = projector.projectItems(output, QueryRows.Symbols.of(listOf(row))) as QueryProjection.Projected
        val item = projected.values.single() as QueryResultItemDocument.ExactSymbol
        assertEquals(fixture.exact, item.ref.token)
        assertSameSelector(
            fixture.selector,
            (fixture.references.restoreExact(item.ref.token, lease) as CanonicalSelectorDecoding.Decoded).value,
        )
        val evidence = item.matches!!.values.single() as QueryTextMatchDocument.IndexedWord
        assertEquals("host", evidence.word.value)
        assertEquals("host()", evidence.context.value)
        assertEquals(0, evidence.range.startInclusive.value)
        assertEquals(4, evidence.range.endExclusive.value)
        assertEquals(1L, evidence.line.value)
        assertTrue(item.connections.values.isEmpty())
        assertSame(
            QueryProjection.Rejected,
            projector.projectItems(QueryOutputDocument.Occurrences, QueryRows.Symbols.of(listOf(row))),
        )
        val bare =
            projector.projectItems(output, QueryRows.Symbols.of(listOf(row.copy(textMatches = QueryTextMatches.Empty))))
                as QueryProjection.Projected
        assertNull((bare.values.single() as QueryResultItemDocument.ExactSymbol).matches)
    }

    private fun matchedRow(fixture: RelationPagingFixture, lease: SemanticReadLease): QuerySymbol {
        val match =
            SymbolTextMatch.fromBoundary(
                    SymbolDiscoveryWord.parse("host").refined(),
                    lease,
                    (fixture.selector.file as SymbolDiscoveryFileIdentity.Workspace).path,
                    0,
                    4,
                    0,
                    6,
                    "host()",
                    0,
                    1,
                )
                .refined()
        return QuerySymbol(
            SymbolDescription.from(fixture.selector),
            emptyList(),
            textMatches = QueryTextMatches.from(listOf(match)).refined(),
        )
    }

    private fun <Value> bounded(values: List<Value>) = BoundedProtocolList.create(values).refined()

    private fun assertSameSelector(expected: SymbolSelector, restored: SymbolSelector) {
        assertEquals(expected.fingerprint, restored.fingerprint)
        assertEquals(expected.lease.identity, restored.lease.identity)
        assertEquals(CanonicalSymbolId.from(expected), CanonicalSymbolId.from(restored))
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = (this as Refinement.Refined).value
}
