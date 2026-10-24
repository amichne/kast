package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryRowWindowDocumentTest {
    @Test
    fun `row window serializes exact retained bounds with a required derived kind`() {
        for ((start, end, total) in listOf(Triple(4, 7, 10), Triple(7, 10, 10), Triple(0, 0, 0), Triple(10, 10, 10))) {
            val window = retained(start, end, total)
            val document = QueryRowWindowDocument.from(window)
            val encoded = Json.encodeToString(QueryRowWindowDocument.serializer(), document)
            val raw = Json.parseToJsonElement(encoded).jsonObject
            assertEquals(setOf("type", "start", "end", "total"), raw.keys)
            assertEquals(if (end == total) "FINAL" else "MORE", raw.getValue("type").jsonPrimitive.content)
            assertEquals(start.toString(), raw.getValue("start").jsonPrimitive.content)
            assertEquals(end.toString(), raw.getValue("end").jsonPrimitive.content)
            assertEquals(total.toString(), raw.getValue("total").jsonPrimitive.content)
            assertEquals(window.start, document.start)
            assertEquals(window.end, document.end)
            assertEquals(window.resultEnd, document.total)
            assertEquals(document, Json.decodeFromString(QueryRowWindowDocument.serializer(), encoded))
        }
    }

    @Test
    fun `a row window need not advance and cannot inherit evidence cursor identity`() {
        val document = QueryRowWindowDocument.from(retained(4, 4, 10))
        assertEquals(QueryRowWindowKind.MORE, document.type)
        assertEquals(cursor(4), document.start)
        assertEquals(cursor(4), document.end)
        assertEquals(cursor(10), document.total)
        assertEquals(
            QueryResultCursorSerializer.descriptor,
            QueryRowWindowDocument.serializer().descriptor.getElementDescriptor(1),
        )
    }

    @Test
    fun `row decoder rejects invalid bounds contradictory kinds and malformed shapes`() {
        val valid =
            Json.encodeToString(QueryRowWindowDocument.serializer(), QueryRowWindowDocument.from(retained(1, 2, 3)))
        for (malformed in
            listOf(
                valid.replace("\"MORE\"", "\"FINAL\""),
                valid.replace("\"MORE\"", "\"UNKNOWN\""),
                valid.replace("\"start\":1", "\"start\":-1"),
                valid.replace("\"start\":1", "\"start\":3"),
                valid.replace("\"end\":2", "\"end\":4"),
                valid.replace("\"total\":3", "\"total\":1000001"),
                valid.replace("\"type\":\"MORE\",", ""),
                valid.replace("\"start\":1", "\"start\":null"),
                valid.replace("\"total\":3", "\"total\":3,\"unknown\":true"),
            )) {
            assertThrows(SerializationException::class.java) {
                Json.decodeFromString(QueryRowWindowDocument.serializer(), malformed)
            }
        }
    }

    private fun retained(start: Int, end: Int, total: Int): QueryRetainedPresentationWindow =
        QueryRetainedPresentationWindow.create(
                QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001").refined(),
                cursor(start),
                cursor(end),
                cursor(total),
            )
            .refined()

    private fun cursor(raw: Int): QueryResultCursor = QueryResultCursor.parse(raw).refined()

    private fun <T> Refinement<T, *>.refined(): T = (this as Refinement.Refined).value
}
