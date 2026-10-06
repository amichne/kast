package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryEvidenceWindowDocumentTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `encoded evidence window has required finite kind and independent positions`() {
        val page = QueryEvidenceWindowDocument.create(cursor(100), cursor(200), cursor(1100)).refined()
        val encoded = json.encodeToString(QueryEvidenceWindowDocument.serializer(), page)
        val raw = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("type", "start", "end", "total"), raw.keys)
        assertEquals("MORE", raw.getValue("type").jsonPrimitive.content)
        assertEquals("100", raw.getValue("start").jsonPrimitive.content)
        assertEquals("200", raw.getValue("end").jsonPrimitive.content)
        assertEquals("1100", raw.getValue("total").jsonPrimitive.content)
        assertEquals(cursor(200), page.nextCursor)
        val shortened = page.prefix(5).refined()
        assertEquals(cursor(105), shortened.end)
        assertEquals(cursor(1100), shortened.total)
        val terminal = QueryEvidenceWindowDocument.create(cursor(1100), cursor(1100), cursor(1100)).refined()
        assertEquals(QueryEvidenceWindowKind.FINAL, terminal.type)
        assertNull(terminal.nextCursor)
    }

    @Test
    fun `raw invalid bounds shapes and discriminators fail before evidence window construction`() {
        val valid =
            json.encodeToString(
                QueryEvidenceWindowDocument.serializer(),
                QueryEvidenceWindowDocument.create(cursor(1), cursor(2), cursor(3)).refined(),
            )
        for (malformed in
            listOf(
                valid.replace("\"MORE\"", "\"FINAL\""),
                valid.replace("\"MORE\"", "\"UNKNOWN\""),
                valid.replace("\"start\":1", "\"start\":-1"),
                valid.replace("\"end\":2", "\"end\":4"),
                valid.replace("\"start\":1", "\"start\":3"),
                valid.replace("\"type\":\"MORE\",", ""),
                valid.replace("\"start\":1", "\"start\":null"),
                valid.replace("\"total\":3", "\"total\":3,\"unknown\":true"),
            )) {
            assertThrows(SerializationException::class.java) {
                json.decodeFromString(QueryEvidenceWindowDocument.serializer(), malformed)
            }
        }
        assertEquals(Refinement.Rejected(QueryEvidenceCursorFailure.OUT_OF_RANGE), QueryEvidenceCursor.parse(1_000_001))
        assertEquals(
            Refinement.Rejected(QueryEvidenceWindowFailure.OUTSIDE_RESULT),
            QueryEvidenceWindowDocument.create(cursor(1), cursor(4), cursor(3)),
        )
    }

    private fun cursor(value: Int) = QueryEvidenceCursor.parse(value).refined()

    private fun <T, F> Refinement<T, F>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Invalid evidence window fixture: $failure")
        }
}
