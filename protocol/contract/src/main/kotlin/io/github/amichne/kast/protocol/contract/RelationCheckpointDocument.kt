package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Original relation coverage remains independent of detached output paging. */
@Serializable
enum class RelationPreparedCoverageDocument {
    @SerialName("complete") COMPLETE,
    @SerialName("resumable") RESUMABLE,
    @SerialName("terminal_incomplete") TERMINAL_INCOMPLETE,
}

@Serializable
sealed interface RelationCheckpointDocument {
    val token: RelationContinuationDocument

    @Serializable
    @SerialName("upstream")
    data class Upstream(override val token: RelationContinuationDocument) : RelationCheckpointDocument

    @Serializable
    @SerialName("retained_output")
    data class RetainedOutput(
        override val token: RelationContinuationDocument,
        val upstream: RelationPreparedCoverageDocument,
    ) : RelationCheckpointDocument
}

internal fun RelationCheckpointDocument.admitCoverage(
    limitations: List<RelationLimitationDocument>,
    nextAction: ReadResumeActionDocument,
): Refinement<RelationCheckpointDocument, RelationReadQualificationFailure> =
    when {
        token.value.startsWith(RelationContinuationDocument.OUTPUT_PREFIX) !=
            (this is RelationCheckpointDocument.RetainedOutput) ->
            Refinement.Rejected(RelationReadQualificationFailure.CONTINUATION_KIND_MISMATCH)
        this is RelationCheckpointDocument.RetainedOutput && nextAction != ReadResumeActionDocument.RESUME ->
            Refinement.Rejected(RelationReadQualificationFailure.UNSUPPORTED_NEXT_ACTION)
        this is RelationCheckpointDocument.RetainedOutput &&
            upstream == RelationPreparedCoverageDocument.COMPLETE &&
            limitations.any { it !in OUTPUT_LIMITATIONS } ->
            Refinement.Rejected(RelationReadQualificationFailure.RETAINED_COVERAGE_CONFLICT)
        else -> Refinement.Refined(this)
    }

private val OUTPUT_LIMITATIONS =
    setOf(RelationLimitationDocument.RESULT_LIMIT_REACHED, RelationLimitationDocument.BYTE_LIMIT_REACHED)
