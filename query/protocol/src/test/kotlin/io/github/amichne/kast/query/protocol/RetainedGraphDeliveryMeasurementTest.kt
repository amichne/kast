package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.QueryEvidenceCursor
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RetainedGraphDeliveryMeasurementTest {
    @Test
    fun `identical canonical wire partitions preserve RPC count and decrease emitted projection bytes`() = runTest {
        val fixture = RetainedGraphDeliveryFixture()
        val issued = fixture.issue()
        var cursor = 0
        var pages = 0
        var candidateReads = 0
        var wireBytes = 0L
        var verboseBytes = 0L
        var compactBytes = 0L
        val verbosePages = mutableListOf<JsonObject>()
        val compactPages = mutableListOf<JsonObject>()
        while (cursor < 53) {
            var selected: MeasuredPage? = null
            for (limit in 53 - cursor downTo 1) {
                val candidate = measure(fixture, issued.reference, ToolOutputDetail.VERBOSE, cursor, limit)
                candidateReads++
                if (candidate.canonicalBytes <= 20_000) {
                    selected = candidate
                    break
                }
            }
            val full = selected ?: error("The frozen canonical allowance cannot emit one exact record")
            val rows = full.document.getValue("items").jsonArray.size
            val compact = measure(fixture, issued.reference, ToolOutputDetail.COMPACT, cursor, rows)
            assertEquals(full.canonicalBytes, compact.canonicalBytes)
            assertTrue(rows > 0)
            pages++
            wireBytes += full.canonicalBytes
            verboseBytes += full.emittedBytes
            compactBytes += compact.emittedBytes
            verbosePages += full.document
            compactPages += compact.document
            cursor += rows
        }
        assertEquals(53, cursor)
        assertEquals(0, fixture.semanticCalls)
        assertTrue(compactBytes < verboseBytes)
        captureGraphDeliveryPages(verbosePages, compactPages)
        val replyBytes = encodedGraphDeliveryRepliesBytes(compactPages)
        assertTrue(replyBytes <= 262_144, "All 53 occurrence rows must fit the unchanged aggregate allowance")
        assertTrue(replyBytes <= 245_760, "The same delivery must preserve 16 KiB of aggregate headroom")
        println("D3 OCCURRENCES typed_reply_bytes=$replyBytes aggregate_cap=262144 headroom=${262_144 - replyBytes}")
        println(
            "D3 canonical_page_cap=20000 identical_partitions=$pages canonical_wire_bytes=$wireBytes " +
                "verbose_emitted_bytes=$verboseBytes compact_emitted_bytes=$compactBytes " +
                "verbose_witnesses=106 compact_witnesses=53 candidate_reads=$candidateReads " +
                "semantic_provider_calls=0 physical_rpcs=NOT_RUN"
        )
    }

    @Test
    fun `equal emitted page allowances reduce compact bytes and repeated witnesses`() = runTest {
        val fixture = RetainedGraphDeliveryFixture()
        val issued = fixture.issue()
        val full = drain(fixture, issued.reference, ToolOutputDetail.VERBOSE)
        val compact = drain(fixture, issued.reference, ToolOutputDetail.COMPACT)
        assertEquals(53, full.rows)
        assertEquals(53, compact.rows)
        assertEquals(53, full.observations)
        assertEquals(53, compact.observations)
        assertTrue(compact.emittedBytes < full.emittedBytes)
        assertTrue(compact.emittedWitnesses < full.emittedWitnesses)
        assertEquals(0, fixture.semanticCalls)
        println(
            "D3 emitted_page_cap=20000 verbose=$full compact=$compact semantic_provider_calls=0 physical_rpcs=NOT_RUN"
        )
    }

    /** This experiment fits the emitted CLI document. The host fitter remains a separate integration obligation. */
    private suspend fun drain(
        fixture: RetainedGraphDeliveryFixture,
        reference: QueryResultReference,
        detail: ToolOutputDetail,
    ): DeliveryCount {
        var cursor = 0
        var count = DeliveryCount()
        while (cursor < 53) {
            var selected: MeasuredPage? = null
            for (limit in 53 - cursor downTo 1) {
                val page = measure(fixture, reference, detail, cursor, limit)
                count = count.copy(candidateReads = count.candidateReads + 1)
                if (page.emittedBytes <= 20_000) {
                    selected = page
                    break
                }
            }
            val page = selected ?: error("The frozen allowance cannot emit one exact record")
            val rows = page.document.getValue("items").jsonArray.size
            assertTrue(rows > 0)
            val graph = page.document["relation_graph"]?.jsonObject
            val observations = (graph ?: page.document).getValue("reference_observations").jsonArray.size
            val witnesses = graph?.getValue("witnesses")?.jsonArray?.size ?: rows + observations
            count =
                count.copy(
                    pages = count.pages + 1,
                    rows = count.rows + rows,
                    observations = count.observations + observations,
                    emittedBytes = count.emittedBytes + page.emittedBytes,
                    canonicalWireBytes = count.canonicalWireBytes + page.canonicalBytes,
                    emittedWitnesses = count.emittedWitnesses + witnesses,
                )
            cursor += rows
        }
        return count
    }

    private suspend fun measure(
        fixture: RetainedGraphDeliveryFixture,
        reference: QueryResultReference,
        detail: ToolOutputDetail,
        cursor: Int,
        limit: Int,
    ): MeasuredPage {
        val page =
            fixture.protocol.executePage(
                QueryRunRequest.ReadResult.occurrences(
                    reference,
                    QueryResultCursor.parse(cursor).graphFixtureValue(),
                    evidenceCursor = QueryEvidenceCursor.parse(cursor).graphFixtureValue(),
                ),
                fixture.source.authority,
                fixture.budget.copy(
                    resources =
                        fixture.budget.resources.copy(resultLimit = ResultLimit.parse(limit).graphFixtureValue())
                ),
            ) as OperationOutcome.Complete
        val document =
            (CanonicalQueryCliDocuments.project(page) as ProjectedOperationOutcome.Complete)
                .document
                .present(detail)
                .value
        val wire = (CanonicalOperationWireBindings.queryRun.encodeOutcome(page) as WireEncoding.Encoded).document
        return MeasuredPage(
            Json.parseToJsonElement(document).jsonObject,
            document.toByteArray(Charsets.UTF_8).size,
            wire.toByteArray(Charsets.UTF_8).size,
        )
    }
}

private data class MeasuredPage(val document: JsonObject, val emittedBytes: Int, val canonicalBytes: Int)

private data class DeliveryCount(
    val pages: Int = 0,
    val rows: Int = 0,
    val observations: Int = 0,
    val emittedBytes: Int = 0,
    val canonicalWireBytes: Int = 0,
    val emittedWitnesses: Int = 0,
    val candidateReads: Int = 0,
)
