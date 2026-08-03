package io.github.amichne.kast.api.protocol

import io.github.amichne.kast.api.contract.result.ReplacementOutboundEvidence
import io.github.amichne.kast.api.contract.result.ReplacementProofDimension

enum class ReplacementProofLimitation {
    TARGET_IDENTITY_UNPROVEN,
    OVERLOAD_AMBIGUOUS,
    UNSUPPORTED_TARGET_KIND,
    ZERO_REPLACEMENT_DECLARATIONS,
    MULTIPLE_REPLACEMENT_DECLARATIONS,
    UNSUPPORTED_REPLACEMENT_KIND,
    UNSUPPORTED_REPLACEMENT_CONTENT,
    PROPOSED_DECLARATION_SYNTAX_INVALID,
    COMPILER_SIGNATURE_UNPROVEN,
    UNSUPPORTED_DECLARATION_ANNOTATION,
    SIGNATURE_DRIFT,
    OUTBOUND_REFERENCE_UNRESOLVED,
    OUTBOUND_REFERENCE_MISMATCH,
    UNSUPPORTED_REFERENCE_KIND,
    OUTBOUND_CARDINALITY_MISMATCH,
    PROPOSED_PSI_TRAVERSAL_INCOMPLETE,
    SOURCE_CONTEXT_CHANGED,
    SOURCE_IMAGE_UNPROVEN,
    GENERATION_CHANGED,
}

class ReplacementProofFailureEvidence private constructor(
    val limitations: List<ReplacementProofLimitation>,
    val outboundEvidence: ReplacementOutboundEvidence.Limited,
) {
    companion object {
        fun of(
            vararg limitations: ReplacementProofLimitation,
            knownMinimumCount: Int = 0,
        ): ReplacementProofFailureEvidence {
            val exactLimitations = limitations.toSet().sortedBy(ReplacementProofLimitation::ordinal)
            require(exactLimitations.isNotEmpty()) { "Replacement proof failure needs at least one limitation" }
            return ReplacementProofFailureEvidence(
                limitations = exactLimitations,
                outboundEvidence = ReplacementOutboundEvidence.Limited.of(
                    knownMinimumCount = knownMinimumCount,
                    dimensions = exactLimitations.map(ReplacementProofLimitation::failedDimension),
                ),
            )
        }
    }
}

private fun ReplacementProofLimitation.failedDimension(): ReplacementProofDimension = when (this) {
    ReplacementProofLimitation.TARGET_IDENTITY_UNPROVEN -> ReplacementProofDimension.EXACT_TARGET_IDENTITY
    ReplacementProofLimitation.UNSUPPORTED_TARGET_KIND -> ReplacementProofDimension.SUPPORTED_TARGET_KIND
    ReplacementProofLimitation.ZERO_REPLACEMENT_DECLARATIONS,
    ReplacementProofLimitation.MULTIPLE_REPLACEMENT_DECLARATIONS,
    ReplacementProofLimitation.UNSUPPORTED_REPLACEMENT_KIND,
    ReplacementProofLimitation.UNSUPPORTED_REPLACEMENT_CONTENT,
    ReplacementProofLimitation.PROPOSED_DECLARATION_SYNTAX_INVALID,
    -> ReplacementProofDimension.SINGLE_SUPPORTED_PROPOSED_DECLARATION
    ReplacementProofLimitation.COMPILER_SIGNATURE_UNPROVEN,
    ReplacementProofLimitation.UNSUPPORTED_DECLARATION_ANNOTATION,
    ReplacementProofLimitation.SIGNATURE_DRIFT,
    -> ReplacementProofDimension.COMPILER_SIGNATURE_EQUAL
    ReplacementProofLimitation.OVERLOAD_AMBIGUOUS -> ReplacementProofDimension.EVERY_CALL_EXACT
    ReplacementProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED ->
        ReplacementProofDimension.EVERY_REFERENCE_COMPILER_RESOLVED
    ReplacementProofLimitation.OUTBOUND_REFERENCE_MISMATCH ->
        ReplacementProofDimension.EVERY_REFERENCE_TARGET_MATCHED
    ReplacementProofLimitation.UNSUPPORTED_REFERENCE_KIND ->
        ReplacementProofDimension.NO_UNSUPPORTED_REFERENCE_KIND
    ReplacementProofLimitation.OUTBOUND_CARDINALITY_MISMATCH ->
        ReplacementProofDimension.EXACT_OUTBOUND_CARDINALITY
    ReplacementProofLimitation.PROPOSED_PSI_TRAVERSAL_INCOMPLETE ->
        ReplacementProofDimension.PROPOSED_PSI_TRAVERSAL_EXHAUSTIVE
    ReplacementProofLimitation.SOURCE_CONTEXT_CHANGED -> ReplacementProofDimension.SOURCE_CONTEXT_HASH_BOUND
    ReplacementProofLimitation.SOURCE_IMAGE_UNPROVEN -> ReplacementProofDimension.SOURCE_CONTEXT_HASH_BOUND
    ReplacementProofLimitation.GENERATION_CHANGED -> ReplacementProofDimension.SEMANTIC_GENERATION_UNCHANGED
}

class ReplacementProofIncompleteException(
    val evidence: ReplacementProofFailureEvidence,
    message: String = "Replacement semantic proof is incomplete",
) : AnalysisException(
    statusCode = 409,
    errorCode = "REPLACEMENT_PROOF_INCOMPLETE",
    message = message,
    retryable = evidence.limitations.any { limitation ->
        limitation == ReplacementProofLimitation.GENERATION_CHANGED ||
            limitation == ReplacementProofLimitation.SOURCE_CONTEXT_CHANGED
    },
    details = buildMap {
        put("limitations", evidence.limitations.joinToString(",") { limitation -> limitation.name })
        put("knownMinimumCount", evidence.outboundEvidence.cardinality.knownMinimumCount.toString())
        put(
            "failedDimensions",
            evidence.outboundEvidence.dimensions.joinToString(",") { dimension -> dimension.name },
        )
    },
)
