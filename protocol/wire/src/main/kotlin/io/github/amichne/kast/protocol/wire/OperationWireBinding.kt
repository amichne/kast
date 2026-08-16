package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement

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
     * Proof transition: `AdmittedWireRequest -> WireDecoding<Request>`.
     *
     * Establishes that the admitted request names this binding's exact schema and canonical
     * operation before returning a generated-serializer-decoded request. [WireFailure] is the
     * closed expected failure. Raw request fields may leave this type only through the operation
     * handler boundary.
     */
    fun decodeRequest(request: AdmittedWireRequest): WireDecoding<Request> {
        when (val admission = admitBindingIdentity(request.schema, request.operation)) {
            BindingIdentityAdmission.Admitted -> Unit
            is BindingIdentityAdmission.Rejected ->
                return WireDecoding.Rejected(admission.failure)
        }
        return decodeValue(serializers.request, request.value, WireValueRole.REQUEST)
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
        val envelope = when (val admission = admitWireEnvelope(document)) {
            is WireEnvelopeAdmission.Admitted -> admission.envelope
            is WireEnvelopeAdmission.Rejected -> return WireDecoding.Rejected(admission.failure)
        }
        when (val admission = admitBindingIdentity(envelope.schema, envelope.operation)) {
            BindingIdentityAdmission.Admitted -> Unit
            is BindingIdentityAdmission.Rejected ->
                return WireDecoding.Rejected(admission.failure)
        }
        val body = envelope.body
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
     * Proof transition: `SchemaIdentity + CanonicalOperation -> BindingIdentityAdmission`.
     *
     * Establishes this binding's exact schema and operation pair. [WireFailure.UnknownSchema] and
     * [WireFailure.UnexpectedOperation] are the closed expected failures. Raw identity extraction
     * is permitted only at [WireRequestEnvelope] and outcome-envelope admission.
     */
    private fun admitBindingIdentity(
        observedSchema: SchemaIdentity,
        observedOperation: CanonicalOperation,
    ): BindingIdentityAdmission {
        if (observedSchema != schema) {
            return BindingIdentityAdmission.Rejected(WireFailure.UnknownSchema(observedSchema))
        }
        if (observedOperation != operation) {
            return BindingIdentityAdmission.Rejected(
                WireFailure.UnexpectedOperation(operation, observedOperation),
            )
        }
        return BindingIdentityAdmission.Admitted
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

private sealed interface BindingIdentityAdmission {
    data object Admitted : BindingIdentityAdmission

    data class Rejected(
        val failure: WireFailure,
    ) : BindingIdentityAdmission
}

private sealed interface EvidenceOperationAdmission {
    data object Admitted : EvidenceOperationAdmission

    data class Rejected(
        val failure: WireFailure,
    ) : EvidenceOperationAdmission
}
