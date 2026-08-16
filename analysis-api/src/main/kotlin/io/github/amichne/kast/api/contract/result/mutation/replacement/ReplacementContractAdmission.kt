package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.protocol.AnalysisException

/**
 * Closed result of refining replacement wire or factory input into a proof-carrying contract value.
 */
sealed interface ReplacementContractAdmission<out Value> {
    data class Admitted<Value>(
        val value: Value,
    ) : ReplacementContractAdmission<Value>

    data class Rejected(
        val failure: ReplacementContractFailure,
    ) : ReplacementContractAdmission<Nothing>
}

/**
 * Finite reasons that replacement contract input can fail admission.
 */
enum class ReplacementContractFailure {
    DECLARATION_SHA256_INVALID,
    BODY_SHA256_INVALID,
    COMPILER_MODEL_GENERATION_NEGATIVE,
    DECLARATION_SLICE_EMPTY,
    BODY_SLICE_EMPTY,
    COMPILER_CONTEXT_NOT_SORTED,
    COMPILER_CONTEXT_DUPLICATE_PATH,
    TARGET_NOT_FUNCTION,
    SOURCE_RANGE_TARGET_MISMATCH,
    SOURCE_RANGE_BEFORE_DECLARATION,
    SOURCE_FILE_HASH_INVALID,
    COMPILER_CONTEXT_CONTAINS_TARGET,
    SIGNATURE_DRIFT,
    SIGNATURE_NOT_FUNCTION,
    DECLARATION_LENGTH_INVALID,
    BODY_LENGTH_INVALID,
    DECLARATION_SLICE_OUT_OF_BOUNDS,
    BODY_SLICE_OUT_OF_BOUNDS,
    OUTBOUND_CARDINALITY_MISMATCH,
    OUTBOUND_REFERENCE_RANGE_INVALID,
    OUTBOUND_REFERENCE_RANGE_DUPLICATE,
    EDIT_RANGE_MISMATCH,
    EDIT_BODY_LENGTH_MISMATCH,
    EDIT_BODY_HASH_MISMATCH,
    EDIT_OUTBOUND_TEXT_MISMATCH,
    FILE_IMAGE_SET_MISMATCH,
    FILE_HASH_PREIMAGE_MISMATCH,
    POSTIMAGE_UNCHANGED,
    POSTIMAGE_REPLAY_INVALID,
}

/**
 * Typed serialization-boundary projection of [ReplacementContractFailure].
 *
 * Raw replacement JSON is permitted only at the kotlinx.serialization boundary. The server maps
 * this finite exception to its typed invalid-request protocol response.
 */
class ReplacementContractWireException(
    val failure: ReplacementContractFailure,
) : AnalysisException(
    statusCode = 400,
    errorCode = "INVALID_REPLACEMENT_CONTRACT",
    message = "Invalid exact replacement contract: ${failure.name}",
    details = mapOf("failure" to failure.name),
)

/**
 * Serialization boundary projection from [ReplacementContractAdmission] to its admitted value.
 *
 * The finite [ReplacementContractFailure] remains available through
 * [ReplacementContractWireException]. Raw extraction is permitted only inside a replacement
 * serializer.
 */
internal fun <Value> ReplacementContractAdmission<Value>.wireValue(): Value = when (this) {
    is ReplacementContractAdmission.Admitted -> value
    is ReplacementContractAdmission.Rejected -> throw ReplacementContractWireException(failure)
}
