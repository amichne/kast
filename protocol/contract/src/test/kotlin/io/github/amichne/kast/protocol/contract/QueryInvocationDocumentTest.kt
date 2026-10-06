package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryInvocationDocumentTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `complete retained prefix encodes coverage independent preview and required discriminators`() {
        val document =
            QueryInvocationDocument.create(3, QueryPreviewDocument.Prefix(1, 450), QueryInvocationStop.COMPLETED)
                .refined()
        val encoded =
            json.parseToJsonElement(json.encodeToString(QueryInvocationDocument.serializer(), document)).jsonObject
        assertEquals(setOf("accumulated_row_count", "preview", "stop"), encoded.keys)
        assertEquals(3, encoded.getValue("accumulated_row_count").jsonPrimitive.int)
        val preview = encoded.getValue("preview").jsonObject
        assertEquals(setOf("type", "row_count", "encoded_bytes"), preview.keys)
        assertEquals("PREFIX", preview.getValue("type").jsonPrimitive.content)
        assertEquals(1, preview.getValue("row_count").jsonPrimitive.int)
        assertEquals(450L, preview.getValue("encoded_bytes").jsonPrimitive.long)
        val stop = encoded.getValue("stop").jsonObject
        assertEquals(setOf("type"), stop.keys)
        assertEquals("COMPLETED", stop.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `every finite stop encodes its owning case and exact failure`() {
        QueryInvocationStop.entries.forEach { stop ->
            val failure =
                if (stop == QueryInvocationStop.INVALID_STATE || stop == QueryInvocationStop.NON_ADVANCING)
                    QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.CONTINUATION_STALE_BASIS)
                else null
            val value = QueryInvocationDocument.create(0, QueryPreviewDocument.Inline(0, 2), stop, failure).refined()
            val encoded = json.encodeToString(QueryInvocationDocument.serializer(), value)
            assertTrue(encoded.contains("\"type\":\"$stop\""))
            if (failure != null)
                assertTrue(
                    encoded.contains("\"failure\":{\"type\":\"EXECUTION\",\"reason\":\"CONTINUATION_STALE_BASIS\"}")
                )
            else assertFalse(encoded.contains("failure"))
            assertEquals(value, json.decodeFromString(QueryInvocationDocument.serializer(), encoded))
        }
    }

    @Test
    fun `malformed and contradictory raw invocation metadata is rejected before domain construction`() {
        val prefix =
            json.encodeToString(
                QueryInvocationDocument.serializer(),
                QueryInvocationDocument.create(3, QueryPreviewDocument.Prefix(1, 450), QueryInvocationStop.COMPLETED)
                    .refined(),
            )
        val empty =
            json.encodeToString(
                QueryInvocationDocument.serializer(),
                QueryInvocationDocument.create(0, QueryPreviewDocument.Inline(0, 2), QueryInvocationStop.COMPLETED)
                    .refined(),
            )
        val invalid =
            listOf(
                prefix.replace("\"accumulated_row_count\":3", "\"accumulated_row_count\":-1"),
                prefix.replace("PREFIX", "INLINE"),
                prefix.replace("\"accumulated_row_count\":3", "\"accumulated_row_count\":1"),
                empty.replace("\"encoded_bytes\":2", "\"encoded_bytes\":1"),
                empty.replace("COMPLETED", "INVALID_STATE"),
                empty.replace("COMPLETED", "completed"),
                prefix.replace("\"type\":\"PREFIX\",", ""),
                empty.dropLast(1) + ",\"unexpected\":true}",
            )
        invalid.forEach {
            assertThrows(SerializationException::class.java) {
                json.decodeFromString(QueryInvocationDocument.serializer(), it)
            }
        }
    }

    private fun <T, F> Refinement<T, F>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejection: $failure")
        }
}
