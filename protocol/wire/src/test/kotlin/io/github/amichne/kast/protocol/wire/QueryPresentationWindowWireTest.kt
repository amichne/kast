package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
