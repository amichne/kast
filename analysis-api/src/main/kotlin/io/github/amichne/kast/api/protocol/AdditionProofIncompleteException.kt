package io.github.amichne.kast.api.protocol

import kotlinx.serialization.Serializable

@Serializable
enum class AdditionProofLimitation {
    PROJECT_MODEL_INCOMPLETE,
    SOURCE_OWNER_UNPROVEN,
    SOURCE_OWNER_AMBIGUOUS,
    TARGET_PARENT_MISSING,
    TARGET_ALREADY_EXISTS,
    TARGET_FILE_MISSING,
    TARGET_FILE_HASH_CHANGED,
    TARGET_NOT_KOTLIN_SOURCE,
    MODULE_CONTEXT_ANCHOR_UNAVAILABLE,
    PROPOSED_SYNTAX_INVALID,
    ZERO_DECLARATIONS,
    MULTIPLE_DECLARATIONS,
    UNSUPPORTED_TOP_LEVEL_DECLARATION,
    COMPILER_COLLISION_SCOPE_INCOMPLETE,
    DECLARATION_COLLISION,
    OUTBOUND_REFERENCE_UNRESOLVED,
    OUTBOUND_REFERENCE_MISMATCH,
    OVERLOAD_AMBIGUOUS,
    REBINDING_SCOPE_INCOMPLETE,
    IMPLICIT_LOOKUP_UNACCOUNTED,
    JAVA_REBINDING_UNPROVEN,
    GENERATION_CHANGED,
    PROJECT_MODEL_CHANGED,
    CLASSPATH_CHANGED,
    SOURCE_CONTEXT_CHANGED,
    FILE_BOTTOM_UNAVAILABLE,
    NEWLINE_POLICY_UNPROVEN,
    POSTIMAGE_MISMATCH,
}

class AdditionProofIncompleteException private constructor(
    val limitations: List<AdditionProofLimitation>,
    message: String,
) : AnalysisException(
    statusCode = 409,
    errorCode = "ADDITION_PROOF_INCOMPLETE",
    message = message,
    retryable = limitations.any(AdditionProofLimitation::isRetryable),
    details = mapOf(
        "limitations" to limitations.joinToString(",") { it.name },
    ),
) {
    companion object {
        fun of(
            vararg limitations: AdditionProofLimitation,
            message: String = "Addition semantic proof is incomplete",
        ): AdditionProofIncompleteException {
            val exactLimitations = limitations.toSet().sortedBy(AdditionProofLimitation::ordinal)
            require(exactLimitations.isNotEmpty()) { "Addition proof failure needs at least one limitation" }
            return AdditionProofIncompleteException(exactLimitations, message)
        }
    }
}

private fun AdditionProofLimitation.isRetryable(): Boolean = when (this) {
    AdditionProofLimitation.GENERATION_CHANGED,
    AdditionProofLimitation.PROJECT_MODEL_CHANGED,
    AdditionProofLimitation.CLASSPATH_CHANGED,
    AdditionProofLimitation.SOURCE_CONTEXT_CHANGED,
    AdditionProofLimitation.TARGET_FILE_HASH_CHANGED,
    -> true

    else -> false
}
