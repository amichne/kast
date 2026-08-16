package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationId
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.CanonicalOperationResolution
import io.github.amichne.kast.protocol.contract.OperationQualification
import io.github.amichne.kast.protocol.contract.OperationRejection
import io.github.amichne.kast.protocol.contract.OperationRequest
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.contract.SchemaIdentity
import io.github.amichne.kast.protocol.registry.OperationDefinition
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val wireJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

/** A typed operation definition paired with generated serializers for every wire value. */
class OperationWireBinding<
    Request : OperationRequest,
    Result : OperationResult,
    Qualification : OperationQualification,
    Rejection : OperationRejection,
    >(
    val definition: OperationDefinition<Request, Result, *, Qualification, Rejection>,
    private val serializers: GeneratedOperationSerializers<Request, Result, Qualification, Rejection>,
) {
    val operation: CanonicalOperation
        get() = definition.operation

    val schema: SchemaIdentity
        get() = definition.schema

    fun encodeRequest(request: Request): WireEncoding = when (
        val encoded = encodeValue(serializers.request, request, WireValueRole.REQUEST)
    ) {
        is WireValueEncoding.Encoded -> encodeEnvelope(WireBodyDocument.Request(encoded.value))
        is WireValueEncoding.Rejected -> WireEncoding.Rejected(encoded.failure)
    }

    /**
     * Proof transition: `String -> WireDecoding<Request>`.
     *
     * Establishes this binding's schema and canonical operation identity before returning a
     * generated-serializer-decoded request. [WireFailure] is the closed expected failure. Raw
     * request fields may leave this type only through the operation handler boundary.
     */
    fun decodeRequest(document: String): WireDecoding<Request> {
        val body = when (val admission = decodeAndAdmit(document)) {
            is WireEnvelopeAdmission.Admitted -> admission.body
            is WireEnvelopeAdmission.Rejected -> return WireDecoding.Rejected(admission.failure)
        }
        return when (body) {
            is WireBodyDocument.Request ->
                decodeValue(serializers.request, body.value, WireValueRole.REQUEST)
            else -> WireDecoding.Rejected(
                WireFailure.UnexpectedBody(setOf(WireBodyKind.REQUEST), body.kind()),
            )
        }
    }

    fun encodeOutcome(
        outcome: OperationOutcome<Result, Qualification, Rejection>,
    ): WireEncoding = when (outcome) {
        is OperationOutcome.Complete -> encodeComplete(outcome.evidence)
        is OperationOutcome.Qualified ->
            encodeQualified(outcome.evidence, outcome.qualification)
        is OperationOutcome.Rejected -> encodeRejected(outcome.reason)
    }

    /**
     * Proof transition: `String -> WireDecoding<OperationOutcome<Result, Qualification,
     * Rejection>>`.
     *
     * Establishes this binding's schema, canonical operation, evidence generation, generated
     * payload types, and closed semantic outcome variant. [WireFailure] is the closed expected
     * failure. Raw fields may leave this type only through the external result-projection boundary.
     */
    fun decodeOutcome(
        document: String,
    ): WireDecoding<OperationOutcome<Result, Qualification, Rejection>> {
        val body = when (val admission = decodeAndAdmit(document)) {
            is WireEnvelopeAdmission.Admitted -> admission.body
            is WireEnvelopeAdmission.Rejected -> return WireDecoding.Rejected(admission.failure)
        }
        return when (body) {
            is WireBodyDocument.Complete -> decodeComplete(body)
            is WireBodyDocument.Qualified -> decodeQualified(body)
            is WireBodyDocument.Rejected -> decodeRejected(body)
            is WireBodyDocument.Request -> WireDecoding.Rejected(
                WireFailure.UnexpectedBody(
                    setOf(
                        WireBodyKind.COMPLETE,
                        WireBodyKind.QUALIFIED,
                        WireBodyKind.REJECTED,
                    ),
                    WireBodyKind.REQUEST,
                ),
            )
        }
    }

    private fun encodeComplete(evidence: EvidenceEnvelope<Result>): WireEncoding {
        val mismatch = evidenceOperationMismatch(evidence)
        if (mismatch is EvidenceOperationAdmission.Rejected) {
            return WireEncoding.Rejected(mismatch.failure)
        }
        return when (val result = encodeValue(serializers.result, evidence.payload, WireValueRole.RESULT)) {
            is WireValueEncoding.Encoded -> encodeEnvelope(
                WireBodyDocument.Complete(evidence.generation.value, result.value),
            )
            is WireValueEncoding.Rejected -> WireEncoding.Rejected(result.failure)
        }
    }

    private fun encodeQualified(
        evidence: EvidenceEnvelope<Result>,
        qualification: Qualification,
    ): WireEncoding {
        val mismatch = evidenceOperationMismatch(evidence)
        if (mismatch is EvidenceOperationAdmission.Rejected) {
            return WireEncoding.Rejected(mismatch.failure)
        }
        val result = when (
            val encoded = encodeValue(serializers.result, evidence.payload, WireValueRole.RESULT)
        ) {
            is WireValueEncoding.Encoded -> encoded.value
            is WireValueEncoding.Rejected -> return WireEncoding.Rejected(encoded.failure)
        }
        return when (
            val encoded = encodeValue(
                serializers.qualification,
                qualification,
                WireValueRole.QUALIFICATION,
            )
        ) {
            is WireValueEncoding.Encoded -> encodeEnvelope(
                WireBodyDocument.Qualified(evidence.generation.value, result, encoded.value),
            )
            is WireValueEncoding.Rejected -> WireEncoding.Rejected(encoded.failure)
        }
    }

    private fun encodeRejected(rejection: Rejection): WireEncoding = when (
        val encoded = encodeValue(serializers.rejection, rejection, WireValueRole.REJECTION)
    ) {
        is WireValueEncoding.Encoded -> encodeEnvelope(WireBodyDocument.Rejected(encoded.value))
        is WireValueEncoding.Rejected -> WireEncoding.Rejected(encoded.failure)
    }

    private fun decodeComplete(
        body: WireBodyDocument.Complete,
    ): WireDecoding<OperationOutcome<Result, Qualification, Rejection>> =
        when (val evidence = decodeEvidence(body.generation, body.result)) {
            is WireDecoding.Decoded ->
                WireDecoding.Decoded(OperationOutcome.Complete(evidence.value))
            is WireDecoding.Rejected -> evidence
        }

    private fun decodeQualified(
        body: WireBodyDocument.Qualified,
    ): WireDecoding<OperationOutcome<Result, Qualification, Rejection>> {
        val evidence = when (val decoded = decodeEvidence(body.generation, body.result)) {
            is WireDecoding.Decoded -> decoded.value
            is WireDecoding.Rejected -> return decoded
        }
        return when (
            val qualification = decodeValue(
                serializers.qualification,
                body.qualification,
                WireValueRole.QUALIFICATION,
            )
        ) {
            is WireDecoding.Decoded -> WireDecoding.Decoded(
                OperationOutcome.Qualified(evidence, qualification.value),
            )
            is WireDecoding.Rejected -> qualification
        }
    }

    private fun decodeRejected(
        body: WireBodyDocument.Rejected,
    ): WireDecoding<OperationOutcome<Result, Qualification, Rejection>> = when (
        val rejection = decodeValue(serializers.rejection, body.rejection, WireValueRole.REJECTION)
    ) {
        is WireDecoding.Decoded ->
            WireDecoding.Decoded(OperationOutcome.Rejected(rejection.value))
        is WireDecoding.Rejected -> rejection
    }

    /**
     * Proof transition: `Long + JsonElement -> WireDecoding<EvidenceEnvelope<Result>>`.
     *
     * Establishes a non-negative evidence generation, this binding's canonical operation, and a
     * generated-serializer-decoded result. [WireFailure] is the closed expected failure. Raw wire
     * values do not escape this boundary.
     */
    private fun decodeEvidence(
        rawGeneration: Long,
        rawResult: JsonElement,
    ): WireDecoding<EvidenceEnvelope<Result>> {
        val generation = when (val refined = EvidenceGeneration.parse(rawGeneration)) {
            is Refinement.Refined -> refined.value
            is Refinement.Rejected -> return WireDecoding.Rejected(
                WireFailure.InvalidEvidenceGeneration(refined.failure),
            )
        }
        return when (val result = decodeValue(serializers.result, rawResult, WireValueRole.RESULT)) {
            is WireDecoding.Decoded -> WireDecoding.Decoded(
                EvidenceEnvelope(operation.id, generation, result.value),
            )
            is WireDecoding.Rejected -> result
        }
    }

    /**
     * Proof transition: `String -> WireEnvelopeAdmission`.
     *
     * Establishes a structurally valid envelope carrying this binding's refined schema and
     * canonical operation. [WireFailure] is the closed expected failure. Raw envelope values stay
     * inside the wire module.
     */
    private fun decodeAndAdmit(document: String): WireEnvelopeAdmission {
        val envelope = try {
            wireJson.decodeFromString(WireEnvelopeDocument.serializer(), document)
        } catch (_: SerializationException) {
            return WireEnvelopeAdmission.Rejected(WireFailure.MalformedEnvelope)
        }

        val observedSchema = when (val refined = SchemaIdentity.parse(envelope.schema)) {
            is Refinement.Refined -> refined.value
            is Refinement.Rejected -> return WireEnvelopeAdmission.Rejected(
                WireFailure.InvalidSchemaIdentity(refined.failure),
            )
        }
        if (observedSchema != schema) {
            return WireEnvelopeAdmission.Rejected(WireFailure.UnknownSchema(observedSchema))
        }

        val operationId = when (val refined = OperationId.parse(envelope.operation)) {
            is Refinement.Refined -> refined.value
            is Refinement.Rejected -> return WireEnvelopeAdmission.Rejected(
                WireFailure.InvalidOperationIdentity(refined.failure),
            )
        }
        val observedOperation = when (val resolution = CanonicalOperation.resolve(operationId)) {
            is CanonicalOperationResolution.Known -> resolution.operation
            is CanonicalOperationResolution.Unknown -> return WireEnvelopeAdmission.Rejected(
                WireFailure.UnknownOperation(resolution.id),
            )
        }
        if (observedOperation != operation) {
            return WireEnvelopeAdmission.Rejected(
                WireFailure.UnexpectedOperation(operation, observedOperation),
            )
        }
        return WireEnvelopeAdmission.Admitted(envelope.body)
    }

    private fun encodeEnvelope(body: WireBodyDocument): WireEncoding = try {
        WireEncoding.Encoded(
            wireJson.encodeToString(
                WireEnvelopeDocument.serializer(),
                WireEnvelopeDocument(schema.value, operation.id.value, body),
            ),
        )
    } catch (_: SerializationException) {
        WireEncoding.Rejected(WireFailure.PayloadEncodingFailed(body.valueRole()))
    }

    private fun evidenceOperationMismatch(
        evidence: EvidenceEnvelope<Result>,
    ): EvidenceOperationAdmission = if (evidence.operation == operation.id) {
        EvidenceOperationAdmission.Admitted
    } else {
        when (val resolution = CanonicalOperation.resolve(evidence.operation)) {
            is CanonicalOperationResolution.Known -> EvidenceOperationAdmission.Rejected(
                WireFailure.UnexpectedOperation(operation, resolution.operation),
            )
            is CanonicalOperationResolution.Unknown -> EvidenceOperationAdmission.Rejected(
                WireFailure.UnknownOperation(resolution.id),
            )
        }
    }
}

private fun <Value> encodeValue(
    serializer: KSerializer<Value>,
    value: Value,
    role: WireValueRole,
): WireValueEncoding = try {
    WireValueEncoding.Encoded(wireJson.encodeToJsonElement(serializer, value))
} catch (_: SerializationException) {
    WireValueEncoding.Rejected(WireFailure.PayloadEncodingFailed(role))
}

/**
 * Proof transition: `JsonElement -> WireDecoding<Value>`.
 *
 * Establishes the generated serializer's exact value type. [WireFailure.InvalidPayload] is the
 * closed expected failure. Raw JSON remains inside the wire module.
 */
private fun <Value> decodeValue(
    serializer: KSerializer<Value>,
    value: JsonElement,
    role: WireValueRole,
): WireDecoding<Value> = try {
    WireDecoding.Decoded(wireJson.decodeFromJsonElement(serializer, value))
} catch (_: SerializationException) {
    WireDecoding.Rejected(WireFailure.InvalidPayload(role))
}

private sealed interface WireValueEncoding {
    data class Encoded(
        val value: JsonElement,
    ) : WireValueEncoding

    data class Rejected(
        val failure: WireFailure,
    ) : WireValueEncoding
}

private sealed interface WireEnvelopeAdmission {
    data class Admitted(
        val body: WireBodyDocument,
    ) : WireEnvelopeAdmission

    data class Rejected(
        val failure: WireFailure,
    ) : WireEnvelopeAdmission
}

private sealed interface EvidenceOperationAdmission {
    data object Admitted : EvidenceOperationAdmission

    data class Rejected(
        val failure: WireFailure,
    ) : EvidenceOperationAdmission
}

@Serializable
private data class WireEnvelopeDocument(
    val schema: String,
    val operation: String,
    val body: WireBodyDocument,
)

@Serializable
private sealed interface WireBodyDocument {
    @Serializable
    @SerialName("request")
    data class Request(
        val value: JsonElement,
    ) : WireBodyDocument

    @Serializable
    @SerialName("complete")
    data class Complete(
        val generation: Long,
        val result: JsonElement,
    ) : WireBodyDocument

    @Serializable
    @SerialName("qualified")
    data class Qualified(
        val generation: Long,
        val result: JsonElement,
        val qualification: JsonElement,
    ) : WireBodyDocument

    @Serializable
    @SerialName("rejected")
    data class Rejected(
        val rejection: JsonElement,
    ) : WireBodyDocument
}

private fun WireBodyDocument.kind(): WireBodyKind = when (this) {
    is WireBodyDocument.Request -> WireBodyKind.REQUEST
    is WireBodyDocument.Complete -> WireBodyKind.COMPLETE
    is WireBodyDocument.Qualified -> WireBodyKind.QUALIFIED
    is WireBodyDocument.Rejected -> WireBodyKind.REJECTED
}

private fun WireBodyDocument.valueRole(): WireValueRole = when (this) {
    is WireBodyDocument.Request -> WireValueRole.REQUEST
    is WireBodyDocument.Complete -> WireValueRole.RESULT
    is WireBodyDocument.Qualified -> WireValueRole.RESULT
    is WireBodyDocument.Rejected -> WireValueRole.REJECTION
}
