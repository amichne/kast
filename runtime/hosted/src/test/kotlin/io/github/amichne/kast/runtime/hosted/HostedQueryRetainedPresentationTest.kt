package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryDocument
import io.github.amichne.kast.protocol.contract.QueryExactFailureDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRetainedPresentationWindow
import io.github.amichne.kast.protocol.contract.QueryRetentionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.SymbolIdDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.protocol.CanonicalQueryProtocol
import io.github.amichne.kast.query.protocol.QueryStateStore
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import io.github.amichne.kast.symbol.contract.SymbolDescription
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedQueryRetainedPresentationTest {
    private val reference = QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001").refined()

    @Test
    fun `retention refusal advances a nonzero presentation cursor only over emitted rows`() {
        val original = page(20, 26, 120)
        val response =
            encodeHostedQueryResponse(original, maximumResults = ResultLimit.parse(2).refined()) {
                HostedOutputRetention.CapacityExceeded
            }
                as HostedResponse.Canonical<*, *, *>
        val fitted = (response.semantic as OperationOutcome.Qualified).evidence.payload as QueryRunResult
        assertEquals(
            listOf("row20", "row21"),
            fitted.items.values.map { (it as QueryResultItemDocument.ExactSymbol).name?.value },
        )
        assertEquals(22, fitted.nextCursor?.value)
        assertEquals(reference, (fitted.retention as QueryResultRetention.Retained).reference)
        assertEquals(20, fitted.presentationWindow?.start?.value)
        assertEquals(22, fitted.presentationWindow?.end?.value)
        val qualification = (response.semantic as OperationOutcome.Qualified).qualification as QueryRunQualification
        assertInstanceOf(QueryQualifiedProgressDocument.RetentionUnavailable::class.java, qualification.progress)
        assertTrue(QueryLimitationDocument.RETENTION_LIMIT_REACHED in qualification.limitations)
    }

    @Test
    fun `byte fitting restores a cursor when the original retained page was final`() {
        val original = page(115, 120, 120, longTokens = true)
        assertNull(original.evidence.payload.nextCursor)
        val response =
            encodeHostedQueryResponse(original, maximumBytes = ReturnedByteLimit.parse(8_000).refined()) {
                HostedOutputRetention.CapacityExceeded
            }
                as HostedResponse.Canonical<*, *, *>
        assertTrue(response.document.toByteArray(Charsets.UTF_8).size <= 8_000)
        val fitted = (response.semantic as OperationOutcome.Qualified).evidence.payload as QueryRunResult
        assertEquals(2, fitted.items.values.size)
        assertEquals(117, fitted.nextCursor?.value)
        val result =
            Json.parseToJsonElement(response.document)
                .jsonObject
                .getValue("body")
                .jsonObject
                .getValue("result")
                .jsonObject
        assertFalse("presentationWindow" in result)
        assertFalse("presentation_window" in result)
    }

    @Test
    fun `a detached final suffix retains its admitted start through later retention refusal`() {
        var suffix: HostedQueryOutcome? = null
        val initial = page(30, 37, 37)
        val first =
            encodeHostedQueryResponse(initial, maximumResults = ResultLimit.parse(2).refined()) {
                suffix = it
                retainedOutput()
            }
                as HostedResponse.Canonical<*, *, *>
        val remaining = (suffix as OperationOutcome.Complete).evidence.payload
        assertEquals(32, remaining.presentationWindow?.start?.value)
        assertNull(remaining.nextCursor)
        val second =
            encodeHostedQueryResponse(requireNotNull(suffix), maximumResults = ResultLimit.parse(2).refined()) {
                HostedOutputRetention.CapacityExceeded
            }
                as HostedResponse.Canonical<*, *, *>
        val firstItems =
            ((first.semantic as OperationOutcome.Qualified).evidence.payload as QueryRunResult).items.values
        val secondResult = (second.semantic as OperationOutcome.Qualified).evidence.payload as QueryRunResult
        assertEquals(34, secondResult.nextCursor?.value)
        assertEquals(32, secondResult.presentationWindow?.start?.value)
        assertEquals(initial.evidence.payload.items.values.take(4), firstItems + secondResult.items.values)
    }

    @Test
    fun `mixed row and evidence pages drain exact identities once while only rows advance the retained cursor`() {
        val original = page(20, 26, 26, longTokens = true)
        val failures = presentationFailures()
        val payload = original.evidence.payload.copy(failures = bounded(failures))
        var pending: HostedQueryOutcome = original.copy(evidence = original.evidence.copy(payload = payload))
        val rows = mutableListOf<QueryResultItemDocument>()
        val evidence = mutableListOf<QueryItemFailureDocument>()
        var cursor = 20
        var requests = 0
        var evidenceOnlyPages = 0
        while (true) {
            requests++
            assertTrue(requests <= 10)
            var suffix: HostedQueryOutcome? = null
            val response =
                encodeHostedQueryResponse(
                    pending,
                    maximumResults = ResultLimit.parse(2).refined(),
                    maximumBytes = ReturnedByteLimit.parse(6500).refined(),
                ) { remainder ->
                    suffix = remainder
                    retainedOutput()
                }
                    as HostedResponse.Canonical<*, *, *>
            assertTrue(response.document.toByteArray().size <= 6500)
            val fitted =
                when (val semantic = response.semantic) {
                    is OperationOutcome.Complete -> semantic.evidence.payload as QueryRunResult
                    is OperationOutcome.Qualified -> semantic.evidence.payload as QueryRunResult
                    is OperationOutcome.Rejected -> error("Unexpected rejection")
                }
            assertEquals(payload.question, fitted.question)
            assertEquals(payload.retention, fitted.retention)
            assertEquals(cursor, fitted.presentationWindow?.start?.value)
            cursor += fitted.items.values.size
            assertEquals(cursor, fitted.presentationWindow?.end?.value)
            assertTrue(fitted.items.values.size <= 2)
            if (fitted.items.values.isEmpty()) {
                evidenceOnlyPages++
                assertTrue(fitted.failures.values.isNotEmpty())
            }
            rows += fitted.items.values
            evidence += fitted.failures.values
            pending = suffix ?: break
        }
        assertEquals(payload.items.values, rows)
        assertEquals(failures, evidence)
        assertEquals(26, cursor)
        assertTrue(evidenceOnlyPages > 1)
    }

    @Test
    fun `terminal fitted cursor reads every unreturned row from the still retained result`() = runTest {
        val fixture = retainedFixture()
        val owner = fixture.owner
        val protocol = fixture.protocol
        val request = fixture.request
        val output = fixture.output
        val budget = fixture.budget
        val initial = protocol.execute(request, owner.authority, budget) as OperationOutcome.Complete
        val originalIds =
            initial.evidence.payload.items.values.map { (it as QueryResultItemDocument.ExactSymbol).rowId }
        val retained = initial.evidence.payload.retention as QueryResultRetention.Retained
        assertEquals(0, initial.evidence.payload.presentationWindow?.start?.value)
        assertEquals(150, initial.evidence.payload.presentationWindow?.resultEnd?.value)
        val finalPage =
            protocol.execute(
                QueryRunRequest.ReadResult.symbols(retained.reference, cursor(100), output),
                owner.authority,
                budget,
            ) as OperationOutcome.Complete
        assertNull(finalPage.evidence.payload.nextCursor)
        val fitted =
            encodeHostedQueryResponse(finalPage, maximumResults = ResultLimit.parse(5).refined()) {
                HostedOutputRetention.CapacityExceeded
            }
                as HostedResponse.Canonical<*, *, *>
        val prefix = (fitted.semantic as OperationOutcome.Complete).evidence.payload as QueryRunResult
        assertEquals(105, prefix.nextCursor?.value)
        val tail =
            protocol.execute(
                QueryRunRequest.ReadResult.symbols(retained.reference, requireNotNull(prefix.nextCursor), output),
                owner.authority,
                budget,
            ) as OperationOutcome.Complete
        val rowIds =
            (prefix.items.values + tail.evidence.payload.items.values).map {
                (it as QueryResultItemDocument.ExactSymbol).rowId
            }
        assertEquals(originalIds.subList(100, 150), rowIds)
        assertEquals(50, rowIds.toSet().size)
    }

    private fun presentationFailures() =
        List(4) { index ->
            QueryItemFailureDocument.ExactReference(
                QueryReferenceDocument.ExactSymbol(text("exact:v3:" + ('b' + index).toString().repeat(3000))),
                QueryExactFailureDocument.AMBIGUOUS_DECLARATION,
            )
        }

    private data class RetainedFixture(
        val owner: RelationPagingFixture,
        val protocol: CanonicalQueryProtocol,
        val request: QueryRunRequest.Run,
        val output: QueryOutputDocument.Symbols,
        val budget: QueryBudget,
    )

    private fun retainedFixture(): RetainedFixture {
        val owner = RelationPagingFixture.live()
        val symbols =
            List(150) {
                QuerySymbol(SymbolDescription.from(owner.selector), emptyList())
            }
        val store = QueryStateStore(clock = { 0L })
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations {
                    QueryExecutionResult.Complete.create(
                        QueryResult(QueryRows.Symbols.of(symbols), emptyList()),
                        QueryCoverage.Complete(QueryCount.parse(150).refined()),
                    )
                },
                owner.references,
                store,
            )
        val output = QueryOutputDocument.Symbols(bounded(listOf(QuerySymbolFieldDocument.NAME)))
        val request = retainedRequest(output)
        val budget = retainedBudget()
        return RetainedFixture(owner, protocol, request, output, budget)
    }

    private fun retainedRequest(output: QueryOutputDocument.Symbols) =
        QueryRunRequest.Run(
            QueryFromDocument.Symbols(
                QueryDiscoveryDocument(
                    QueryMatchDocument.All,
                    QueryScopeDocument(bounded(listOf(text("main"))), null, null),
                    bounded(listOf(QueryDeclarationKindDocument.FUNCTION)),
                )
            ),
            bounded(emptyList()),
            output,
            QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
            retention = QueryRetentionModeDocument.RETAIN,
            completion = io.github.amichne.kast.protocol.contract.QueryCompletionPolicyDocument.Progressive,
        )

    private fun retainedBudget() =
        QueryBudget(
            ResourceBudget(
                ResultLimit.parse(200).refined(),
                WorkUnitLimit.parse(1000).refined(),
                ElapsedTimeLimitMillis.parse(1000).refined(),
            ),
            QueryByteLimit.parse(100_000).refined(),
        )

    internal fun page(
        start: Int,
        end: Int,
        resultEnd: Int,
        longTokens: Boolean = false,
    ): OperationOutcome.Complete<QueryRunResult> {
        val window =
            QueryRetainedPresentationWindow.create(reference, cursor(start), cursor(end), cursor(resultEnd)).refined()
        return OperationOutcome.Complete(
            EvidenceEnvelope(
                CanonicalOperationWireBindings.queryRun.operation.id,
                EvidenceGeneration.parse(1).refined(),
                QueryRunResult(
                    fixtureQueryQuestion(),
                    bounded((start until end).map { item(it, longTokens) }),
                    bounded(emptyList()),
                    retention = QueryResultRetention.Retained(reference),
                    nextCursor = window.nextCursor,
                    presentationOrigin = QueryKnownMinimum.parse(resultEnd).refined(),
                    presentationWindow = window,
                ),
            )
        )
    }

    private fun item(index: Int, longTokens: Boolean): QueryResultItemDocument =
        QueryResultItemDocument.ExactSymbol(
            QueryReferenceDocument.ExactSymbol(text("exact:v3:" + if (longTokens) "a".repeat(3000) else "row$index")),
            SymbolKindDocument.FUNCTION,
            text("row$index"),
            null,
            null,
            bounded(emptyList()),
            SymbolIdDocument.parse("sym:" + "A".repeat(43)).refined(),
        )

    private fun retainedOutput() =
        HostedOutputRetention.Retained(text(HostedQueryContinuations.prefix + "00000000-0000-0000-0000-000000000000"))

    private fun cursor(value: Int) = QueryResultCursor.parse(value).refined()

    private fun text(value: String) = ProtocolText.parse(value).refined()

    private fun <Value> bounded(values: List<Value>): BoundedProtocolList<Value> =
        BoundedProtocolList.create(values).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}

private fun fixtureQueryQuestion(): QueryQuestionDocument {
    fun <Value, Failure> fixtureValue(value: Refinement<Value, Failure>): Value =
        when (value) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> error("Invalid question fixture: ${value.failure}")
        }
    return QueryQuestionDocument(
        QueryFromDocument.Location(
            fixtureValue(ProtocolText.parse("Fixture.kt")),
            fixtureValue(ProtocolOffset.parse(0)),
        ),
        fixtureValue(BoundedProtocolList.create(emptyList())),
        QueryOutputDocument.Symbols(fixtureValue(BoundedProtocolList.create(listOf(QuerySymbolFieldDocument.NAME)))),
    )
}
