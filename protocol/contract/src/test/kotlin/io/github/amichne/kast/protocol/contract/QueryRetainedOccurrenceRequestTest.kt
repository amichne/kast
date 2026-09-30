package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryRetainedOccurrenceRequestTest {
    @Test
    fun `retained occurrence presentation is admitted and encoded as its exact canonical action`() {
        val reference =
            (QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001") as Refinement.Refined).value
        val cursor = (QueryResultCursor.parse(100) as Refinement.Refined).value
        val request = QueryRunRequest.ReadResult.occurrences(reference, cursor)
        val encoded = Json.encodeToString(QueryRunRequest.serializer(), request)
        val fields = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("action", "result", "cursor", "output"), fields.keys)
        assertEquals("read-result", fields.getValue("action").jsonPrimitive.content)
        assertEquals(reference.value, fields.getValue("result").jsonPrimitive.content)
        assertEquals("100", fields.getValue("cursor").jsonPrimitive.content)
        assertEquals(setOf("type"), fields.getValue("output").jsonObject.keys)
        assertEquals("occurrences", fields.getValue("output").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(request, Json.decodeFromString(QueryRunRequest.serializer(), encoded))
        val traversal = QueryRunRequest.ReadResult.traversalRecords(reference, cursor)
        val traversalEncoded = Json.encodeToString(QueryRunRequest.serializer(), traversal)
        assertEquals(
            "traversal_records",
            Json.parseToJsonElement(traversalEncoded)
                .jsonObject
                .getValue("output")
                .jsonObject
                .getValue("type")
                .jsonPrimitive
                .content,
        )
        assertEquals(traversal, Json.decodeFromString(QueryRunRequest.serializer(), traversalEncoded))
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(QueryRunRequest.serializer(), encoded.replace("occurrences", "unknown_output"))
        }
    }
}
