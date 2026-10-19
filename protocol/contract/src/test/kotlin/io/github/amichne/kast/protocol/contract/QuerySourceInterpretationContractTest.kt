package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QuerySourceInterpretationContractTest {
    @Test
    fun `source interpretation always encodes its exact effect evidence contract`() {
        for (json in listOf(Json, Json { encodeDefaults = true })) {
            val encoded =
                json
                    .encodeToJsonElement(
                        QueryResultInterpretationDocument.serializer(),
                        QueryResultInterpretationDocument.SourceRelationships(),
                    )
                    .jsonObject
            assertEquals(setOf("type", "callbackEffects"), encoded.keys)
            assertEquals("SOURCE_RELATIONSHIPS", encoded.getValue("type").jsonPrimitive.content)
            assertEquals("EVIDENCE_ONLY", encoded.getValue("callbackEffects").jsonPrimitive.content)
        }
    }

    @Test
    fun `source interpretation rejects missing null and unknown callback effect claims`() {
        val json = Json { encodeDefaults = true }
        val invalid =
            listOf(json.encodeToString(MissingCallbackEffects())) +
                listOf(null, "COMPLETE", "UNKNOWN").map {
                    json.encodeToString(InvalidCallbackEffects(callbackEffects = it))
                }
        for (input in invalid) {
            assertThrows(SerializationException::class.java) {
                Json.decodeFromString(QueryResultInterpretationDocument.serializer(), input)
            }
        }
    }

    @Serializable private data class MissingCallbackEffects(val type: String = "SOURCE_RELATIONSHIPS")

    @Serializable
    private data class InvalidCallbackEffects(val type: String = "SOURCE_RELATIONSHIPS", val callbackEffects: String?)
}
