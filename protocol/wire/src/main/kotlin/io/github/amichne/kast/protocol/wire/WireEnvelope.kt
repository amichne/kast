package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.OperationId
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.CanonicalOperationResolution
import io.github.amichne.kast.protocol.contract.SchemaIdentity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal val wireJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

/**
 * A request envelope whose schema identity and canonical operation have been refined and whose
 * body is structurally a request.
 *
 * Construction remains inside [WireRequestEnvelope]. The retained raw payload is available only
 * to a matching [OperationWireBinding], which replaces it with the generated request type.
 */
class AdmittedWireRequest internal constructor(
    val schema: SchemaIdentity,
    val operation: CanonicalOperation,
    internal val value: JsonElement,
)

/** Closed result of refining a raw wire document into an [AdmittedWireRequest]. */
sealed interface WireRequestAdmission {
    data class Admitted(
        val request: AdmittedWireRequest,
    ) : WireRequestAdmission

    data class Rejected(
        val failure: WireFailure,
    ) : WireRequestAdmission
}

/** Sole public admission boundary for request-envelope routing identity. */
object WireRequestEnvelope {
    /**
     * Proof transition: `String -> WireRequestAdmission`.
     *
     * Establishes a refined [SchemaIdentity], a known [CanonicalOperation], and a request body
     * without exposing its raw payload. [WireFailure] is the closed expected failure. Raw payload
     * extraction is permitted only inside the matching [OperationWireBinding] decoder.
     */
    fun admit(document: String): WireRequestAdmission = when (
        val admission = admitWireEnvelope(document)
    ) {
        is WireEnvelopeAdmission.Rejected -> WireRequestAdmission.Rejected(admission.failure)
        is WireEnvelopeAdmission.Admitted -> when (val body = admission.envelope.body) {
            is WireBodyDocument.Request -> WireRequestAdmission.Admitted(
                AdmittedWireRequest(
                    schema = admission.envelope.schema,
                    operation = admission.envelope.operation,
                    value = body.value,
                ),
            )
            else -> WireRequestAdmission.Rejected(
                WireFailure.UnexpectedBody(
                    expected = setOf(WireBodyKind.REQUEST),
                    observed = body.kind(),
                ),
            )
        }
    }
}

/**
 * Proof transition: `String -> WireEnvelopeAdmission`.
 *
 * Establishes a structurally valid envelope carrying a refined schema identity and known
 * canonical operation. [WireFailure] is the closed expected failure. Raw envelope and payload
 * values remain inside the wire module.
 */
internal fun admitWireEnvelope(document: String): WireEnvelopeAdmission {
    val envelope = try {
        wireJson.decodeFromString(WireEnvelopeDocument.serializer(), document)
    } catch (_: SerializationException) {
        return WireEnvelopeAdmission.Rejected(WireFailure.MalformedEnvelope)
    }

    val schema = when (val refined = SchemaIdentity.parse(envelope.schema)) {
        is Refinement.Refined -> refined.value
        is Refinement.Rejected -> return WireEnvelopeAdmission.Rejected(
            WireFailure.InvalidSchemaIdentity(refined.failure),
        )
    }
    val operationId = when (val refined = OperationId.parse(envelope.operation)) {
        is Refinement.Refined -> refined.value
        is Refinement.Rejected -> return WireEnvelopeAdmission.Rejected(
            WireFailure.InvalidOperationIdentity(refined.failure),
        )
    }
    val operation = when (val resolution = CanonicalOperation.resolve(operationId)) {
        is CanonicalOperationResolution.Known -> resolution.operation
        is CanonicalOperationResolution.Unknown -> return WireEnvelopeAdmission.Rejected(
            WireFailure.UnknownOperation(resolution.id),
        )
    }
    return WireEnvelopeAdmission.Admitted(
        AdmittedWireEnvelope(schema, operation, envelope.body),
    )
}

internal data class AdmittedWireEnvelope(
    val schema: SchemaIdentity,
    val operation: CanonicalOperation,
    val body: WireBodyDocument,
)

internal sealed interface WireEnvelopeAdmission {
    data class Admitted(
        val envelope: AdmittedWireEnvelope,
    ) : WireEnvelopeAdmission

    data class Rejected(
        val failure: WireFailure,
    ) : WireEnvelopeAdmission
}

@Serializable
internal data class WireEnvelopeDocument(
    val schema: String,
    val operation: String,
    val body: WireBodyDocument,
)

@Serializable
internal sealed interface WireBodyDocument {
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

internal fun WireBodyDocument.kind(): WireBodyKind = when (this) {
    is WireBodyDocument.Request -> WireBodyKind.REQUEST
    is WireBodyDocument.Complete -> WireBodyKind.COMPLETE
    is WireBodyDocument.Qualified -> WireBodyKind.QUALIFIED
    is WireBodyDocument.Rejected -> WireBodyKind.REJECTED
}

internal fun WireBodyDocument.valueRole(): WireValueRole = when (this) {
    is WireBodyDocument.Request -> WireValueRole.REQUEST
    is WireBodyDocument.Complete -> WireValueRole.RESULT
    is WireBodyDocument.Qualified -> WireValueRole.RESULT
    is WireBodyDocument.Rejected -> WireValueRole.REJECTION
}
