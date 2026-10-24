package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRetainedPresentationWindow
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class QueryPresentationWindowWireTest {
    @Test
    fun `wire preserves the independently specified retained window and its exact association`() {
        val result = result()
        val encoded = CanonicalQuerySerializers.result.encode(result, WireValueRole.RESULT) as WireValueEncoding.Encoded
        val expected =
            Json.parseToJsonElement(javaClass.getResource("/query-presentation-window.expected.json")!!.readText())
        assertEquals(expected, encoded.value.jsonObject["presentation_window"])
        assertEquals(
            WireDecoding.Decoded(result),
            CanonicalQuerySerializers.result.decode(encoded.value, WireValueRole.RESULT),
        )
    }

    @Test
    fun `wire omits a window when none was supplied`() {
        val result =
            result().copy(retention = QueryResultRetention.NotRequested, presentationWindow = null, nextCursor = null)
        val encoded = CanonicalQuerySerializers.result.encode(result, WireValueRole.RESULT) as WireValueEncoding.Encoded
        assertFalse(encoded.value.jsonObject.containsKey("presentation_window"))
        assertEquals(
            WireDecoding.Decoded(result),
            CanonicalQuerySerializers.result.decode(encoded.value, WireValueRole.RESULT),
        )
    }

    @Test
    fun `wire rejects malformed bounds and mismatched retained reference or row count`() {
        val encoded =
            (CanonicalQuerySerializers.result.encode(result(), WireValueRole.RESULT) as WireValueEncoding.Encoded)
                .value
                .toString()
        val window =
            Json.parseToJsonElement(javaClass.getResource("/query-presentation-window.expected.json")!!.readText())
                .toString()
        for (malformed in
            listOf(
                window.replace("\"start\":0", "\"start\":-1"),
                window.replace("\"start\":0", "\"start\":1"),
                window.replace("\"end\":0", "\"end\":2"),
                window.replace("000000000001", "000000000002"),
                window.replace("\"end\":0", "\"end\":1"),
            )) {
            assertEquals(
                WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.RESULT)),
                CanonicalQuerySerializers.result.decode(
                    Json.parseToJsonElement(encoded.replace(window, malformed)),
                    WireValueRole.RESULT,
                ),
            )
        }
    }

    @Test
    fun `public row window and legacy cursor come from retained bounds not cursor metadata`() {
        val original = result()
        val window = requireNotNull(original.presentationWindow)
        val terminal =
            QueryRetainedPresentationWindow.create(window.reference, window.start, window.end, window.end).value()
        for (detail in ToolOutputDetail.entries) {
            val more = publicDocument(original.copy(nextCursor = null), detail)
            assertEquals("MORE", more.getValue("row_window").jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals("0", more.getValue("next_cursor").jsonPrimitive.content)
            val final = publicDocument(original.copy(presentationWindow = terminal), detail)
            assertEquals("FINAL", final.getValue("row_window").jsonObject.getValue("type").jsonPrimitive.content)
            assertFalse(final.containsKey("next_cursor"))
            assertFalse(final.containsKey("presentation_window"))
        }
    }

    @Test
    fun `public projection cannot manufacture row bounds from an unretained cursor`() {
        val unretained = result().copy(retention = QueryResultRetention.NotRequested, presentationWindow = null)
        for (detail in ToolOutputDetail.entries) {
            val document = publicDocument(unretained, detail)
            assertFalse(document.containsKey("row_window"))
            assertFalse(document.containsKey("next_cursor"))
        }
    }

    private fun publicDocument(result: QueryRunResult, detail: ToolOutputDetail): JsonObject {
        val outcome =
            OperationOutcome.Complete(
                EvidenceEnvelope(CanonicalOperation.QUERY_RUN.id, EvidenceGeneration.parse(1).value(), result)
            )
        val projected = CanonicalQueryCliDocuments.project(outcome) as ProjectedOperationOutcome.Complete
        return Json.parseToJsonElement(projected.document.present(detail).value).jsonObject
    }

    private fun result(): QueryRunResult {
        val reference = QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001").value()
        return QueryRunResult(
            question =
                QueryQuestionDocument(
                    QueryFromDocument.Location(ProtocolText.parse("File.kt").value(), ProtocolOffset.parse(0).value()),
                    bounded(emptyList()),
                    QueryOutputDocument.Symbols(bounded(emptyList())),
                ),
            items = bounded(emptyList()),
            failures = bounded(emptyList()),
            retention = QueryResultRetention.Retained(reference),
            presentationWindow =
                QueryRetainedPresentationWindow.create(
                        reference,
                        QueryResultCursor.Start,
                        QueryResultCursor.Start,
                        QueryResultCursor.parse(1).value(),
                    )
                    .value(),
            nextCursor = QueryResultCursor.Start,
        )
    }

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

    private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
}
