package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.protocol.CanonicalQueryProtocol
import io.github.amichne.kast.query.protocol.QueryExecutionClaim
import io.github.amichne.kast.query.protocol.QueryResultRestoration
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import io.github.amichne.kast.query.protocol.executeQueryPage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedRetainedEntryDetailTest {
    @Test
    fun `full large row reaches the retained store before its selector and reconstructs without semantic replay`() =
        runTest {
            val case = largeRetainedCase()
            val reconstructed = reconstruct(case)
            assertEquals(case.summary.detail.selector.sha256.value, entryDigest(reconstructed))
            val connections = Json.parseToJsonElement(reconstructed).jsonObject.getValue("connections").jsonArray
            assertEquals(
                List(500) { 10 + (it % 3) * 2 },
                connections.map {
                    it.jsonObject
                        .getValue("occurrence")
                        .jsonObject
                        .getValue("range")
                        .jsonObject
                        .getValue("startInclusive")
                        .jsonPrimitive
                        .int
                },
            )
            assertEquals(1, case.fixture.providerCalls())
            assertEquals(listOf(0L, 1L, 2L), case.owner.consumed)
            val stored =
                case.fixture.store.restoreResult(case.result, case.owner.authority) as QueryResultRestoration.Restored
            assertEquals(
                case.expected,
                (stored.result as io.github.amichne.kast.query.contract.QueryRetainedResult.Symbols)
                    .symbols
                    .single()
                    .connections,
            )
        }

    @Test
    fun `expired detail selector rejects without implicit semantic replay`() = runTest {
        val fixtures = HostedQueryRetainedPresentationTest()
        var now = 0L
        val fixture = fixtures.retainedFixture(clock = { now }, ttlMillis = 1)
        val initial =
            fixture.protocol.execute(
                fixture.request,
                fixture.owner.authority,
                fixture.budget,
                fixtures.retainedPolicy(),
            ) as OperationOutcome.Complete
        val retained = initial.evidence.payload.retention as QueryResultRetention.Retained
        val serialized =
            io.github.amichne.kast.protocol.wire.QueryEntryDetailSerialization.row(
                initial.evidence.payload.items.values.first()
            )
        val selector =
            io.github.amichne.kast.protocol.contract.QueryEntryDetailSelectorDocument(
                retained.reference,
                io.github.amichne.kast.protocol.contract.QueryEntryDetailSection.CANONICAL_ENTRY_JSON,
                io.github.amichne.kast.protocol.contract.QueryEntryDetailTarget.ROW,
                cursor(0),
                io.github.amichne.kast.protocol.contract.QueryEntryDetailKind.EXACT_SYMBOL,
                text(entryDigest(serialized.text)),
                ProtocolOffset.parse(0).refined(),
            )
        val request = QueryRunRequest.ReadResult.detail(retained.reference, selector, fixture.output)
        val valid =
            fixture.protocol.execute(request, fixture.owner.authority, fixture.budget, fixtures.retainedPolicy())
        assertInstanceOf(OperationOutcome.Complete::class.java, valid)
        now = 2_000_000L
        val read =
            fixture.protocol.execute(
                QueryRunRequest.ReadResult.detail(retained.reference, selector, fixture.output),
                fixture.owner.authority,
                fixture.budget,
                fixtures.retainedPolicy(),
            )
        assertInstanceOf(OperationOutcome.Rejected::class.java, read)
        assertEquals(
            io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument.RESULT_UNAVAILABLE,
            ((read as OperationOutcome.Rejected).reason
                    as io.github.amichne.kast.protocol.contract.QueryRunRejection.ExecutionRejected)
                .reason,
        )
        assertEquals(1, fixture.providerCalls())
    }

    @Test
    fun `detail reads use original retained authority and never replay semantic work`() = runTest {
        val fixtures = HostedQueryRetainedPresentationTest()
        val fixture = fixtures.retainedFixture()
        val initial =
            fixture.protocol.execute(
                fixture.request,
                fixture.owner.authority,
                fixture.budget,
                fixtures.retainedPolicy(),
            ) as OperationOutcome.Complete
        val payload = initial.evidence.payload
        val retained = payload.retention as QueryResultRetention.Retained
        val originalRow = payload.items.values.first()
        val serialized = io.github.amichne.kast.protocol.wire.QueryEntryDetailSerialization.row(originalRow)
        val selector =
            io.github.amichne.kast.protocol.contract.QueryEntryDetailSelectorDocument(
                retained.reference,
                io.github.amichne.kast.protocol.contract.QueryEntryDetailSection.CANONICAL_ENTRY_JSON,
                io.github.amichne.kast.protocol.contract.QueryEntryDetailTarget.ROW,
                cursor(0),
                io.github.amichne.kast.protocol.contract.QueryEntryDetailKind.EXACT_SYMBOL,
                text(entryDigest(serialized.text)),
                ProtocolOffset.parse(0).refined(),
            )
        val request = QueryRunRequest.ReadResult.detail(retained.reference, selector, fixture.output)
        val read = fixture.protocol.execute(request, fixture.owner.authority, fixture.budget, fixtures.retainedPolicy())
        assertInstanceOf(OperationOutcome.Complete::class.java, read)
        val detail = encodeHostedQueryResponse(read) as HostedResponse.Canonical<*, *, *>
        val result = ((detail.semantic as OperationOutcome.Complete).evidence.payload as QueryRunResult).detail!!
        assertEquals(serialized.text, result.text.value)
        assertEquals(serialized.text.length, result.end.value)
        assertAuthorityRejects(fixture, request, selector)
    }

    private suspend fun assertAuthorityRejects(
        fixture: HostedQueryRetainedPresentationTest.RetainedFixture,
        request: QueryRunRequest.ReadResult,
        selector: io.github.amichne.kast.protocol.contract.QueryEntryDetailSelectorDocument,
    ) {
        val fixtures = HostedQueryRetainedPresentationTest()
        val stale =
            fixture.protocol.execute(
                request,
                RelationPagingFixture.live().authority,
                fixture.budget,
                fixtures.retainedPolicy(),
            )
        assertInstanceOf(OperationOutcome.Rejected::class.java, stale)
        val foreign =
            QueryRunRequest.ReadResult.detail(
                QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000099").refined(),
                selector,
                fixture.output,
            )
        assertInstanceOf(
            OperationOutcome.Rejected::class.java,
            fixture.protocol.execute(foreign, fixture.owner.authority, fixture.budget, fixtures.retainedPolicy()),
        )
        fixture.store.clear()
        assertInstanceOf(
            OperationOutcome.Rejected::class.java,
            fixture.protocol.execute(request, fixture.owner.authority, fixture.budget, fixtures.retainedPolicy()),
        )
        assertEquals(1, fixture.providerCalls())
    }

    private data class LargeRetainedCase(
        val owner: RelationPagingFixture,
        val fixture: HostedQueryRetainedPresentationTest.RetainedFixture,
        val expected: List<io.github.amichne.kast.relation.contract.RelationFact>,
        val result: QueryResultReference,
        val summary: QueryResultItemDocument.DeferredDetail,
    )

    private suspend fun largeRetainedCase(): LargeRetainedCase {
        val fixtures = HostedQueryRetainedPresentationTest()
        val owner = RelationPagingFixture.live()
        val facts =
            (owner.firstPage() as io.github.amichne.kast.relation.contract.RelationReadResult.Qualified).batch.facts
        val expected = List(500) { facts[it % 3] }
        val fixture = fixtures.retainedFixture(owner = owner, connections = expected, rowCount = 1)
        val policy = fixtures.retainedPolicy(retainedBytes = 128_000_000)
        val initial = fixture.protocol.execute(fixture.request, owner.authority, fixture.budget, policy)
        assertInstanceOf(OperationOutcome.Complete::class.java, initial, initial.toString().take(3000))
        val result =
            ((initial as OperationOutcome.Complete).evidence.payload.retention as QueryResultRetention.Retained)
                .reference
        val stored =
            (fixture.store.restoreResult(result, owner.authority) as QueryResultRestoration.Restored).result
                as io.github.amichne.kast.query.contract.QueryRetainedResult.Symbols
        assertEquals(expected, stored.symbols.single().connections)
        val read =
            fixture.protocol.execute(
                QueryRunRequest.ReadResult.symbols(result, output = fixture.output),
                owner.authority,
                fixture.budget,
                policy,
            )
        val response =
            encodeHostedQueryResponse(read, maximumBytes = ReturnedByteLimit.parse(8_000).refined())
                as HostedResponse.Canonical<*, *, *>
        val page = (response.semantic as OperationOutcome.Complete).evidence.payload as QueryRunResult
        val summary = page.items.values.single() as QueryResultItemDocument.DeferredDetail
        assertEquals(result, summary.detail.selector.result)
        return LargeRetainedCase(owner, fixture, expected, result, summary)
    }

    private suspend fun reconstruct(case: LargeRetainedCase): String {
        val reader = CommittedDetailReads(case)
        val pieces = mutableListOf<String>()
        var offset = 0
        var total = Int.MAX_VALUE
        while (offset < total) {
            assertTrue(pieces.size < 1000)
            val selector = case.summary.detail.selector.copy(offset = ProtocolOffset.parse(offset).refined())
            val chunk = reader.read(selector)
            assertEquals(offset, chunk.selector.offset.value)
            assertTrue(chunk.end.value > offset)
            pieces += chunk.text.value
            offset = chunk.end.value
            total = chunk.total.value
        }
        reader.assertSettled(pieces.size)
        return pieces.joinToString("")
    }

    private class CommittedDetailReads(private val case: LargeRetainedCase) {
        private lateinit var claim: QueryExecutionClaim
        private var preparations = 0
        private var unexpectedExecutions = 0
        private val protocol =
            CanonicalQueryProtocol(
                QueryOperations {
                    unexpectedExecutions++
                    error("A retained detail cannot execute semantic work")
                },
                case.owner.references,
                case.fixture.store,
                io.github.amichne.kast.query.protocol.QueryExecutionPublication { store, active, _ ->
                    assertEquals(case.fixture.store, store)
                    claim = active
                    preparations++
                    io.github.amichne.kast.query.protocol.QueryExecutionPublicationResult.PREPARED
                },
            )

        suspend fun read(
            selector: io.github.amichne.kast.protocol.contract.QueryEntryDetailSelectorDocument
        ): io.github.amichne.kast.protocol.contract.QueryEntryDetailDocument {
            val before = preparations
            val result =
                protocol.executeQueryPage(
                    QueryRunRequest.ReadResult.detail(case.result, selector, case.fixture.output),
                    case.owner.authority,
                    case.fixture.budget,
                )
            assertInstanceOf(OperationOutcome.Complete::class.java, result)
            assertEquals(before + 1, preparations)
            var fitted: io.github.amichne.kast.query.protocol.QueryPublicationPageCharge.Encoded? = null
            val response =
                encodeHostedQueryResponse(
                    result,
                    maximumBytes = (ReturnedByteLimit.parse(8_000) as Refinement.Refined).value,
                    published = { fitted = it },
                )
                    as HostedResponse.Canonical<*, *, *>
            val charge = checkNotNull(fitted) { "A selected detail must reach the publication owner" }
            assertEquals(
                io.github.amichne.kast.query.protocol.QueryPublicationCommit.Committed,
                case.fixture.store.commitPublication(claim, charge.page, charge),
            )
            case.fixture.store.releasePublication(claim)
            assertTrue(response.document.toByteArray(Charsets.UTF_8).size <= 8_000)
            return ((response.semantic as OperationOutcome.Complete).evidence.payload as QueryRunResult).detail!!
        }

        fun assertSettled(pages: Int) {
            assertEquals(pages, preparations)
            assertEquals(0, unexpectedExecutions)
        }
    }

    private fun cursor(value: Int) = QueryResultCursor.parse(value).refined()

    private fun text(value: String) = ProtocolText.parse(value).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Invalid detail fixture: $failure")
        }
}
