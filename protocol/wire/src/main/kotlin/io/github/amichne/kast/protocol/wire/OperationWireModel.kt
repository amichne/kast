package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.EvidenceGenerationFailure
import io.github.amichne.kast.kernel.OperationId
import io.github.amichne.kast.kernel.PermanentIdentityFailure
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.OperationQualification
import io.github.amichne.kast.protocol.contract.OperationRejection
import io.github.amichne.kast.protocol.contract.OperationRequest
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.contract.SchemaIdentity
import io.github.amichne.kast.protocol.contract.SchemaIdentityFailure

/** Generated structured codecs whose type parameters exactly match one operation definition. */
internal data class GeneratedOperationSerializers<
    Request : OperationRequest,
    Result : OperationResult,
    Qualification : OperationQualification,
    Rejection : OperationRejection,
    >(
    val request: WireValueCodec<Request>,
    val result: WireValueCodec<Result>,
    val qualification: WireValueCodec<Qualification>,
    val rejection: WireValueCodec<Rejection>,
)

sealed interface WireEncoding {
    data class Encoded(
        val document: String,
    ) : WireEncoding

    data class Rejected(
        val failure: WireFailure,
    ) : WireEncoding
}

sealed interface WireDecoding<out Value> {
    data class Decoded<Value>(
        val value: Value,
    ) : WireDecoding<Value>

    data class Rejected(
        val failure: WireFailure,
    ) : WireDecoding<Nothing>
}

enum class WireValueRole {
    REQUEST,
    RESULT,
    QUALIFICATION,
    REJECTION,
}

enum class WireBodyKind {
    REQUEST,
    COMPLETE,
    QUALIFIED,
    REJECTED,
}

sealed interface WireFailure {
    data object MalformedEnvelope : WireFailure

    data class InvalidSchemaIdentity(
        val failure: SchemaIdentityFailure,
    ) : WireFailure

    data class UnknownSchema(
        val schema: SchemaIdentity,
    ) : WireFailure

    data class InvalidOperationIdentity(
        val failure: PermanentIdentityFailure,
    ) : WireFailure

    data class UnknownOperation(
        val operationId: OperationId,
    ) : WireFailure

    data class UnexpectedOperation(
        val expected: CanonicalOperation,
        val observed: CanonicalOperation,
    ) : WireFailure

    data class UnexpectedBody(
        val expected: Set<WireBodyKind>,
        val observed: WireBodyKind,
    ) : WireFailure

    data class InvalidEvidenceGeneration(
        val failure: EvidenceGenerationFailure,
    ) : WireFailure

    data class InvalidPayload(
        val role: WireValueRole,
    ) : WireFailure

    data class PayloadEncodingFailed(
        val role: WireValueRole,
    ) : WireFailure
}
