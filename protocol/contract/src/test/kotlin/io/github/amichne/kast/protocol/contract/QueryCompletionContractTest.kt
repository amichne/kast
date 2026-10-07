package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryCompletionContractTest {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    @Test
    fun `complete only has required explicit discriminator and model`() {
        val policy = QueryCompletionPolicyDocument.CompleteOnly(QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1)
        val encoded = json.encodeToJsonElement(QueryCompletionPolicyDocument.serializer(), policy).jsonObject
        assertEquals(setOf("type", "model"), encoded.keys)
        assertEquals("COMPLETE_ONLY", encoded.getValue("type").jsonPrimitive.content)
        assertEquals("COMPILER_RESOLVED_STATIC_V1", encoded.getValue("model").jsonPrimitive.content)
        for (invalid in
            listOf(
                InvalidPolicy(),
                InvalidPolicy("COMPLETE_ONLY"),
                InvalidPolicy("complete_only", "COMPILER_RESOLVED_STATIC_V1"),
                InvalidPolicy("COMPLETE_ONLY", "UNKNOWN"),
                InvalidPolicy("PROGRESSIVE", "COMPILER_RESOLVED_STATIC_V1"),
            )) {
            val raw = Json.encodeToString(InvalidPolicy.serializer(), invalid)
            assertThrows(SerializationException::class.java) {
                json.decodeFromString(QueryCompletionPolicyDocument.serializer(), raw)
            }
        }
    }

    @Test
    fun `original qualifications retain a nonempty canonical limitation set`() {
        val values = listOf(QueryLimitationDocument.RELATION_INCOMPLETE, QueryLimitationDocument.EXECUTION_INCOMPLETE)
        val admitted = (QueryCompletionLimitationsDocument.from(values) as Refinement.Refined).value
        val encoded = json.encodeToString(QueryCompletionLimitationsDocument.serializer(), admitted)
        assertEquals(
            listOf("RELATION_INCOMPLETE", "EXECUTION_INCOMPLETE"),
            json.decodeFromString(ListSerializer(String.serializer()), encoded),
        )
        for (values in
            listOf(
                emptyList(),
                listOf("RELATION_INCOMPLETE", "RELATION_INCOMPLETE"),
                listOf("EXECUTION_INCOMPLETE", "RELATION_INCOMPLETE"),
                listOf("UNKNOWN"),
            )) {
            val raw = json.encodeToString(ListSerializer(String.serializer()), values)
            assertThrows(SerializationException::class.java) {
                json.decodeFromString(QueryCompletionLimitationsDocument.serializer(), raw)
            }
        }
    }
}

/** Deliberately invalid typed boundary fixture; nullable fields exercise absence. */
@Serializable private data class InvalidPolicy(val type: String? = null, val model: String? = null)
