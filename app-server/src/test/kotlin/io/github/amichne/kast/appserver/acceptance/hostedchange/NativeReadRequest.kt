package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.appserver.schema.CompiledJsonSchema
import io.github.amichne.kast.appserver.schema.JsonSchemaViolationEvidence
import io.github.amichne.kast.kernel.Validation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Tool arguments and canonical CLI results are the contract-defined dynamic payloads. */
@Serializable
internal sealed interface NativeReadRequest {
    val tool: String

    @Serializable
    @SerialName("invoke")
    data class Invoke(override val tool: String, val arguments: JsonObject) : NativeReadRequest

    @Serializable
    @SerialName("validate")
    data class Validate(override val tool: String, val document: JsonElement) : NativeReadRequest

    @Serializable
    @SerialName("validate_envelope")
    data class ValidateEnvelope(override val tool: String, val envelope: JsonElement) : NativeReadRequest
}

internal val readRequestJson = Json { classDiscriminator = "action" }
private val validationJson = Json { encodeDefaults = true }

@Serializable
private data class NativeReadValidationEnvelope(
    val document: JsonElement,
    val status: NativeReadProcessStatus = NativeReadProcessStatus.COMPLETED,
)

@Serializable
private enum class NativeReadProcessStatus {
    @SerialName("completed") COMPLETED
}

internal fun validateNativeReadEnvelope(schema: CompiledJsonSchema, envelope: JsonElement): NativeReadResponse =
    validateNativeReadOutput(schema, envelope)

internal fun validateNativeReadOutput(schema: CompiledJsonSchema, document: JsonElement): NativeReadResponse =
    when (
        val admitted =
            schema.admit(
                validationJson.encodeToJsonElement(
                    NativeReadValidationEnvelope.serializer(),
                    NativeReadValidationEnvelope(document),
                )
            )
    ) {
        is Validation.Validated -> NativeReadResponse.ValidationAccepted(admitted.value.schemaDigest.value)
        is Validation.Rejected ->
            NativeReadResponse.ValidationRejected(JsonSchemaViolationEvidence.from(admitted.failures).toDocument())
    }
