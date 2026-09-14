package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.appserver.schema.CompiledJsonSchema
import io.github.amichne.kast.appserver.schema.JsonSchemaViolationField
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NativeReadValidationTest {
    @Test
    fun `actual provider envelope is validated without manufacturing its status`() {
        val json = Json { encodeDefaults = true }
        val schema = (CompiledJsonSchema.compile(
            json.encodeToJsonElement(ValidationSchema.serializer(), ValidationSchema()).jsonObject
        ) as Refinement.Refined).value
        val encoded = json.encodeToJsonElement(ActualEnvelope.serializer(), ActualEnvelope())
        assertTrue(validateNativeReadEnvelope(schema, encoded) is NativeReadResponse.ValidationAccepted)
        for (malformed in listOf(
            """{"document":"canonical output"}""",
            """{"status":"unknown","document":"canonical output"}""",
            """{"status":"completed","document":"canonical output","extra":true}""",
            """{"status":"completed","document":1}""",
        )) {
            assertTrue(validateNativeReadEnvelope(schema, Json.parseToJsonElement(malformed))
                is NativeReadResponse.ValidationRejected)
        }
    }

    @Test
    fun `validator admits the exact outer envelope and retains bounded failure evidence`() {
        val json = Json { encodeDefaults = true }
        val schema =
            (CompiledJsonSchema.compile(
                    json.encodeToJsonElement(ValidationSchema.serializer(), ValidationSchema()).jsonObject
                ) as Refinement.Refined)
                .value
        val valid =
            validateNativeReadOutput(schema, JsonPrimitive("canonical output")) as NativeReadResponse.ValidationAccepted
        assertEquals(schema.digest.value, valid.schemaDigest)
        val invalid = validateNativeReadOutput(schema, JsonPrimitive(1)) as NativeReadResponse.ValidationRejected
        assertTrue(invalid.evidence.observations.any { it.field == JsonSchemaViolationField.DOCUMENT })
    }

    @Test
    fun `invocation and validation have disjoint typed request shapes`() {
        val request = NativeReadRequest.Validate("query_symbols", JsonPrimitive(1))
        val encoded = readRequestJson.encodeToString(NativeReadRequest.serializer(), request)
        assertEquals(request, readRequestJson.decodeFromString(NativeReadRequest.serializer(), encoded))
        assertThrows<SerializationException> { readRequestJson.decodeFromString<NativeReadRequest.Invoke>(encoded) }
    }
}

@Serializable
private data class ActualEnvelope(val status: String = "completed", val document: String = "canonical output")

@Serializable
private data class ValidationSchema(
    val type: String = "object",
    val additionalProperties: Boolean = false,
    val required: List<String> = listOf("status", "document"),
    val properties: ValidationProperties = ValidationProperties(),
)

@Serializable
private data class ValidationProperties(
    val status: StatusProperty = StatusProperty(),
    val document: StringProperty = StringProperty(),
)

@Serializable private data class StatusProperty(val type: String = "string", val const: String = "completed")

@Serializable private data class StringProperty(val type: String = "string")
