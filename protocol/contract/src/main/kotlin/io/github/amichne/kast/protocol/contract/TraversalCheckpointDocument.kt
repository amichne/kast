package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Original graph coverage; its finite causes remain in the unchanged qualification limitations. */
@Serializable
enum class TraversalPreparedCoverageDocument {
    @SerialName("complete") COMPLETE,
    @SerialName("resumable") RESUMABLE,
    @SerialName("terminal_incomplete") TERMINAL_INCOMPLETE,
}

@Serializable
sealed interface TraversalCheckpointDocument {
    val token: TraversalContinuationDocument

    @Serializable
    @SerialName("upstream")
    data class Upstream(override val token: TraversalContinuationDocument) : TraversalCheckpointDocument

    @Serializable
    @SerialName("retained_output")
    data class RetainedOutput(
        override val token: TraversalContinuationDocument,
        val upstream: TraversalPreparedCoverageDocument,
    ) : TraversalCheckpointDocument
}

internal fun TraversalCheckpointDocument.admitCoverage(
    limitations: List<TraversalLimitationDocument>
): Refinement<TraversalCheckpointDocument, TraversalRunQualificationFailure> {
    val output = token.value.startsWith(TraversalContinuationDocument.OUTPUT_PREFIX)
    if (output != (this is TraversalCheckpointDocument.RetainedOutput))
        return Refinement.Rejected(TraversalRunQualificationFailure.CONTINUATION_KIND_MISMATCH)
    val coverage =
        when (this) {
            is TraversalCheckpointDocument.Upstream -> TraversalPreparedCoverageDocument.RESUMABLE
            is TraversalCheckpointDocument.RetainedOutput -> upstream
        }
    return when {
        coverage == TraversalPreparedCoverageDocument.COMPLETE && limitations.any { it !in OUTPUT_LIMITATIONS } ->
            Refinement.Rejected(TraversalRunQualificationFailure.RETAINED_COVERAGE_CONFLICT)
        coverage == TraversalPreparedCoverageDocument.RESUMABLE &&
            limitations.any { it in NON_RESUMABLE_LIMITATIONS } ->
            Refinement.Rejected(TraversalRunQualificationFailure.TERMINAL_LIMITATION_RESUMABLE)
        coverage == TraversalPreparedCoverageDocument.TERMINAL_INCOMPLETE && !limitations.hasTerminalTraversalCause() ->
            Refinement.Rejected(TraversalRunQualificationFailure.TERMINAL_WITHOUT_TERMINAL_LIMITATION)
        else -> Refinement.Refined(this)
    }
}

private val OUTPUT_LIMITATIONS =
    setOf(TraversalLimitationDocument.RECORD_LIMIT_REACHED, TraversalLimitationDocument.BYTE_LIMIT_REACHED)
private val NON_RESUMABLE_LIMITATIONS =
    setOf(TraversalLimitationDocument.DEPTH_LIMIT_REACHED, TraversalLimitationDocument.NO_PROGRESS)
private val TERMINAL_LIMITATIONS =
    NON_RESUMABLE_LIMITATIONS +
        setOf(TraversalLimitationDocument.ONE_HOP_INCOMPLETE, TraversalLimitationDocument.TIME_LIMIT_REACHED)

internal fun List<TraversalLimitationDocument>.hasTerminalTraversalCause(): Boolean = any { it in TERMINAL_LIMITATIONS }
