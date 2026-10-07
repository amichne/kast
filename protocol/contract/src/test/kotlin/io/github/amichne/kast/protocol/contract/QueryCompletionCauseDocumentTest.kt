package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryCompletionCauseDocumentTest {
    private val graph =
        QueryCallbackGraphFailureDocument(
            QueryCallbackGraphCauseDocument.Unavailable(QueryCallbackFlowCauseDocument.STORED_CALLBACK),
            QueryCallbackObservationOrigin.RELATION,
            (ProtocolOffset.parse(0) as Refinement.Refined).value,
            (ProtocolOffset.parse(1) as Refinement.Refined).value,
        )

    @Test
    fun `completion cases encode only their required proof details`() {
        for ((type, cause) in
            listOf(
                "INCOMPLETE_EXECUTION" to QueryCompletionCauseDocument.IncompleteExecution,
                "ITEM_FAILURE" to QueryCompletionCauseDocument.ItemFailure,
                "OMITTED_EVIDENCE" to QueryCompletionCauseDocument.OmittedEvidence,
            )) {
            val encoded = Json.encodeToJsonElement(QueryCompletionCauseDocument.serializer(), cause).jsonObject
            assertEquals(setOf("type"), encoded.keys)
            assertEquals(type, encoded.getValue("type").jsonPrimitive.content)
        }
        val callback = QueryCompletionCauseDocument.CallbackGraphUnproven(graph)
        val encoded = Json.encodeToJsonElement(QueryCompletionCauseDocument.serializer(), callback).jsonObject
        assertEquals(setOf("type", "graphFailure"), encoded.keys)
        assertEquals("CALLBACK_GRAPH_UNPROVEN", encoded.getValue("type").jsonPrimitive.content)
        assertEquals(
            "STORED_CALLBACK",
            encoded
                .getValue("graphFailure")
                .jsonObject
                .getValue("cause")
                .jsonObject
                .getValue("cause")
                .jsonPrimitive
                .content,
        )
        assertEquals(callback, Json.decodeFromJsonElement(QueryCompletionCauseDocument.serializer(), encoded))
    }

    @Test
    fun `completion boundary excludes missing callback detail and unrelated scalar detail`() {
        for (invalid in
            listOf(
                InvalidCompletionCause("UNKNOWN"),
                InvalidCompletionCause("CALLBACK_GRAPH_UNPROVEN"),
                InvalidCompletionCause("INCOMPLETE_EXECUTION", graph),
            )) {
            val encoded = Json.encodeToJsonElement(InvalidCompletionCause.serializer(), invalid)
            assertThrows(SerializationException::class.java) {
                Json.decodeFromJsonElement(QueryCompletionCauseDocument.serializer(), encoded)
            }
        }
    }
}

@Serializable
private data class InvalidCompletionCause(val type: String, val graphFailure: QueryCallbackGraphFailureDocument? = null)
