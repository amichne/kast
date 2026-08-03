package io.github.amichne.kast.api.protocol

enum class MutationPostconditionLimitation {
    POSTIMAGE_MISMATCH,
    POSTIMAGE_UNREADABLE,
    SEMANTIC_SOURCE_UNAVAILABLE,
    TARGET_IDENTITY_MISMATCH,
    REFERENCE_COVERAGE_INCOMPLETE,
    OCCURRENCE_SET_MISMATCH,
    SIGNATURE_MISMATCH,
    OUTBOUND_SET_MISMATCH,
    SOURCE_OWNER_CHANGED,
    PROJECT_MODEL_CHANGED,
    CLASSPATH_CHANGED,
    SOURCE_CONTEXT_CHANGED,
    DECLARATION_SET_MISMATCH,
    COLLISION_OR_REBINDING_CHANGED,
    GENERATION_CHANGED,
}

class MutationPostconditionFailedException private constructor(
    val limitations: List<MutationPostconditionLimitation>,
    message: String,
) : AnalysisException(
    statusCode = 409,
    errorCode = "MUTATION_POSTCONDITION_FAILED",
    message = message,
    retryable = limitations.any { limitation ->
        limitation == MutationPostconditionLimitation.GENERATION_CHANGED ||
            limitation == MutationPostconditionLimitation.SOURCE_CONTEXT_CHANGED ||
            limitation == MutationPostconditionLimitation.POSTIMAGE_UNREADABLE ||
            limitation == MutationPostconditionLimitation.SEMANTIC_SOURCE_UNAVAILABLE
    },
    details = mapOf("limitations" to limitations.joinToString(",", transform = Enum<*>::name)),
) {
    companion object {
        fun of(
            vararg limitations: MutationPostconditionLimitation,
            message: String = "Mutation postcondition verification failed",
        ): MutationPostconditionFailedException {
            val exact = limitations.toSet().sortedBy(MutationPostconditionLimitation::ordinal)
            require(exact.isNotEmpty())
            return MutationPostconditionFailedException(exact, message)
        }
    }
}
