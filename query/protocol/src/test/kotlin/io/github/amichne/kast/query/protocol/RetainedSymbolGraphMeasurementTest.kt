package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.QueryEvidenceCursor
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RetainedSymbolGraphMeasurementTest {
    @Test
    fun `symbol graph decreases emitted bytes at identical canonical wire partitions`() = runTest {
        val fixture = RetainedSymbolGraphFixture()
        val issued = fixture.issue()
        val counts = SymbolGraphCount()
        val verbose = mutableListOf<JsonObject>()
        val compact = mutableListOf<JsonObject>()
        while (counts.rows < 53) {
            var selected: SymbolGraphPage? = null
            for (limit in 53 - counts.rows downTo 1) {
                val candidate = measure(fixture, issued.reference, counts.rows, limit)
                counts.candidateReads++
                if (candidate.wireBytes <= 20_000) {
                    selected = candidate
                    break
                }
            }
            val page = selected ?: error("The canonical allowance cannot emit one exact symbol row")
            val rows = page.verbose.getValue("items").jsonArray.size
            assertTrue(rows > 0)
            assertEquals(rows, page.compact.getValue("items").jsonArray.size)
            verbose += page.verbose
            compact += page.compact
            assertLosslessRows(page.verbose, page.compact)
            counts.add(rows, page)
        }
        assertEquals(53, counts.rows)
        assertEquals(0, fixture.retained.semanticCalls)
        assertTrue(counts.compactBytes < counts.verboseBytes)
        val emittedReplyBytes = encodedGraphDeliveryRepliesBytes(compact)
        println(
            "D3 SYMBOLS typed_reply_bytes=$emittedReplyBytes aggregate_cap=262144 " +
                "headroom=${262_144 - emittedReplyBytes}"
        )
        captureGraphDeliveryPages(verbose, compact, "symbol-canonical-partitions.json")
        println("D3 SYMBOLS canonical_page_cap=20000 $counts semantic_provider_calls=0 physical_rpcs=NOT_RUN")
        assertTrue(emittedReplyBytes <= 262_144, "All 53 symbol rows must fit the unchanged aggregate allowance")
        assertTrue(emittedReplyBytes <= 245_760, "The same delivery must preserve 16 KiB of aggregate headroom")
    }

    private suspend fun measure(
        fixture: RetainedSymbolGraphFixture,
        result: QueryResultReference,
        cursor: Int,
        limit: Int,
    ): SymbolGraphPage {
        val page =
            fixture.retained.protocol.executePage(
                QueryRunRequest.ReadResult.symbols(
                    result,
                    QueryResultCursor.parse(cursor).graphFixtureValue(),
                    output = fixture.selectedOutput,
                    evidenceCursor = QueryEvidenceCursor.parse(cursor).graphFixtureValue(),
                ),
                fixture.retained.source.authority,
                fixture.retained.budget.copy(
                    resources =
                        fixture.retained.budget.resources
                            .bounded()
                            .copy(resultLimit = ResultLimit.parse(limit).graphFixtureValue())
                ),
            ) as OperationOutcome.Complete
        val encoded = (CanonicalOperationWireBindings.queryRun.encodeOutcome(page) as WireEncoding.Encoded).document
        val decoded = (CanonicalOperationWireBindings.queryRun.decodeOutcome(encoded) as WireDecoding.Decoded).value
        val document = (CanonicalQueryCliDocuments.project(decoded) as ProjectedOperationOutcome.Complete).document
        return SymbolGraphPage(
            Json.parseToJsonElement(document.present(ToolOutputDetail.VERBOSE).value).jsonObject,
            Json.parseToJsonElement(document.present(ToolOutputDetail.COMPACT).value).jsonObject,
            encoded.toByteArray(Charsets.UTF_8).size,
        )
    }
}

private data class SymbolGraphPage(val verbose: JsonObject, val compact: JsonObject, val wireBytes: Int)

private data class SymbolGraphCount(
    var rows: Int = 0,
    var pages: Int = 0,
    var candidateReads: Int = 0,
    var canonicalBytes: Long = 0,
    var verboseBytes: Long = 0,
    var compactBytes: Long = 0,
    var verboseEndpointDescriptions: Int = 0,
    var compactEndpointDescriptions: Int = 0,
) {
    fun add(count: Int, page: SymbolGraphPage) {
        rows += count
        pages++
        canonicalBytes += page.wireBytes
        verboseBytes += page.verbose.toString().toByteArray(Charsets.UTF_8).size
        compactBytes += page.compact.toString().toByteArray(Charsets.UTF_8).size
        verboseEndpointDescriptions +=
            page.verbose.getValue("items").jsonArray.sumOf {
                it.jsonObject.getValue("connections").jsonArray.size * 2
            } +
                page.verbose.getValue("reference_observations").jsonArray.sumOf {
                    val owner = it.jsonObject.getValue("ownership").jsonObject
                    1 + if (owner.getValue("type").jsonPrimitive.content == "declaration-owned") 1 else 0
                }
        compactEndpointDescriptions +=
            page.compact.getValue("relation_graph").jsonObject.getValue("nodes").jsonArray.size
    }
}
