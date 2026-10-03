package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.kernel.EvidenceGenerationFailure
import io.github.amichne.kast.kernel.PermanentIdentityFailure
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ImpactAccountingFailure
import io.github.amichne.kast.protocol.contract.SchemaIdentityFailure
import io.github.amichne.kast.protocol.wire.WireBodyKind
import io.github.amichne.kast.protocol.wire.WireFailure
import io.github.amichne.kast.protocol.wire.WireValueRole
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Unknown identities are classified without exposing their raw, unadmitted input. */
@Serializable
internal sealed interface ExistingIdeResponseCause {
    @Serializable @SerialName("STRICT_JSON") data object StrictJson : ExistingIdeResponseCause

    @Serializable
    @SerialName("PREPARED_COMPLETION")
    data class Completion(
        val operation: CanonicalOperation,
        val kind: ExistingIdeCompletionFailureKind,
        val cause: Wire,
    ) : ExistingIdeResponseCause

    @Serializable sealed interface Wire : ExistingIdeResponseCause

    @Serializable
    @SerialName("ADMISSION")
    data class Admission(val failure: ExistingIdeFailure) : ExistingIdeResponseCause

    @Serializable
    @SerialName("LIVE_BASIS")
    data class Basis(val reason: ExistingIdeResponseBasisFailure) : ExistingIdeResponseCause

    @Serializable @SerialName("MALFORMED_ENVELOPE") data object MalformedEnvelope : ExistingIdeResponseCause.Wire

    @Serializable
    @SerialName("INVALID_IMPACT_ACCOUNTING")
    data class Accounting(val cause: ImpactAccountingFailure) : ExistingIdeResponseCause.Wire

    @Serializable
    @SerialName("INVALID_SCHEMA_IDENTITY")
    data class Schema(val cause: SchemaIdentityFailure) : ExistingIdeResponseCause.Wire

    @Serializable @SerialName("UNKNOWN_SCHEMA") data object UnknownSchema : ExistingIdeResponseCause.Wire

    @Serializable
    @SerialName("INVALID_OPERATION_IDENTITY")
    data class OperationIdentity(val cause: PermanentIdentityFailure) : ExistingIdeResponseCause.Wire

    @Serializable @SerialName("UNKNOWN_OPERATION") data object UnknownOperation : ExistingIdeResponseCause.Wire

    @Serializable
    @SerialName("UNEXPECTED_OPERATION")
    data class Operation(val expected: CanonicalOperation, val observed: CanonicalOperation) :
        ExistingIdeResponseCause.Wire

    @Serializable
    @SerialName("UNEXPECTED_BODY")
    data class Body(val expected: Set<WireBodyKind>, val observed: WireBodyKind) : ExistingIdeResponseCause.Wire

    @Serializable
    @SerialName("INVALID_EVIDENCE_GENERATION")
    data class Generation(val cause: EvidenceGenerationFailure) : ExistingIdeResponseCause.Wire

    @Serializable
    @SerialName("INVALID_PAYLOAD")
    data class Payload(val role: WireValueRole) : ExistingIdeResponseCause.Wire

    @Serializable
    @SerialName("PAYLOAD_ENCODING_FAILED")
    data class Encoding(val role: WireValueRole) : ExistingIdeResponseCause.Wire
}

@Serializable
internal enum class ExistingIdeCompletionFailureKind {
    REQUEST_ENCODING_FAILED,
    RESPONSE_DECODING_FAILED,
}

internal fun WireFailure.responseCause(): ExistingIdeResponseCause.Wire =
    when (this) {
        is WireFailure.InvalidImpactAccounting -> ExistingIdeResponseCause.Accounting(cause)
        WireFailure.MalformedEnvelope -> ExistingIdeResponseCause.MalformedEnvelope
        is WireFailure.InvalidSchemaIdentity -> ExistingIdeResponseCause.Schema(failure)
        is WireFailure.UnknownSchema -> ExistingIdeResponseCause.UnknownSchema
        is WireFailure.InvalidOperationIdentity -> ExistingIdeResponseCause.OperationIdentity(failure)
        is WireFailure.UnknownOperation -> ExistingIdeResponseCause.UnknownOperation
        is WireFailure.UnexpectedOperation -> ExistingIdeResponseCause.Operation(expected, observed)
        is WireFailure.UnexpectedBody -> ExistingIdeResponseCause.Body(expected, observed)
        is WireFailure.InvalidEvidenceGeneration -> ExistingIdeResponseCause.Generation(failure)
        is WireFailure.InvalidPayload -> ExistingIdeResponseCause.Payload(role)
        is WireFailure.PayloadEncodingFailed -> ExistingIdeResponseCause.Encoding(role)
    }
