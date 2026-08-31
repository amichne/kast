package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.EvidenceGeneration

/** Explicit request for the one generation-bound repository-topology build. */
data object TopologyBuildRequest : OperationRequest

enum class TopologyBuildStatus {
    PUBLISHED,
    REUSED,
}

enum class TopologyBuildDigestFailure {
    INVALID_SHA256,
}

/** Exact lowercase SHA-256 identity of one successful topology generation. */
@JvmInline
value class TopologyBuildDigest private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<TopologyBuildDigest,
         * TopologyBuildDigestFailure>`.
         *
         * Establishes the exact 64-character lowercase SHA-256 form used by a successful public
         * topology build. [TopologyBuildDigestFailure] is the closed expected failure. Raw digest
         * text may enter only from topology composition or the generated wire boundary.
         */
        fun parse(
            raw: String,
        ): io.github.amichne.kast.kernel.Refinement<
            TopologyBuildDigest,
            TopologyBuildDigestFailure,
        > = if (raw.length == 64 && raw.all { it in '0'..'9' || it in 'a'..'f' }) {
            io.github.amichne.kast.kernel.Refinement.Refined(TopologyBuildDigest(raw))
        } else {
            io.github.amichne.kast.kernel.Refinement.Rejected(
                TopologyBuildDigestFailure.INVALID_SHA256,
            )
        }
    }
}

/** Successful topology snapshot identity; only complete or exactly reusable builds produce it. */
data class TopologyBuildResult(
    val status: TopologyBuildStatus,
    val generation: EvidenceGeneration,
    val digest: TopologyBuildDigest,
) : OperationResult

/** Reserved progress state; synchronous canonical execution never returns qualified topology. */
enum class TopologyBuildQualification : OperationQualification {
    PROGRESS_UNAVAILABLE,
}

enum class TopologySnapshotRejection {
    CONTRACT_VIOLATION,
    STORAGE_UNAVAILABLE,
    CORRUPT_SNAPSHOT,
}

enum class TopologyEnumerationRejection {
    WORKSPACE_UNAVAILABLE,
    SOURCE_ROOT_UNAVAILABLE,
    SOURCE_CONTENT_UNAVAILABLE,
    AMBIGUOUS_SOURCE_ROOT_OWNER,
    CANDIDATE_REJECTED,
}

enum class TopologyExtractionRejection {
    PROJECT_UNAVAILABLE,
    FILE_UNAVAILABLE,
    SOURCE_CONTENT_MOVED,
    NOT_KOTLIN_PSI,
    COMPILER_UNAVAILABLE,
    FACT_REJECTED,
}

enum class TopologyPublicationRejection {
    CONTRACT_VIOLATION,
    STORAGE_UNAVAILABLE,
    SNAPSHOT_CONFLICT,
    CORRUPT_SNAPSHOT,
}

enum class TopologyCoverageProjectionRejection {
    UNREPRESENTABLE_TEXT,
    UNREPRESENTABLE_RANGE,
    UNREPRESENTABLE_CONTENT_HASH,
    UNREPRESENTABLE_COMPILER_EVIDENCE,
    EMPTY_FAILURE,
}

/** Closed public projection retaining the exact expected topology build failure. */
sealed interface TopologyBuildRejection : OperationRejection {
    data object WorkspaceNotReady : TopologyBuildRejection
    data class SnapshotUnavailable(
        val failure: TopologySnapshotRejection,
    ) : TopologyBuildRejection
    data class EnumerationFailed(
        val failure: TopologyEnumerationRejection,
    ) : TopologyBuildRejection
    data class ExtractionFailed(
        val file: ProtocolText,
        val failure: TopologyExtractionRejection,
    ) : TopologyBuildRejection
    data object ExtractionContractViolation : TopologyBuildRejection
    data class CoverageIncomplete(
        val failure: TopologyCoverageFailure,
    ) : TopologyBuildRejection
    data class CoverageProjectionFailed(
        val failure: TopologyCoverageProjectionRejection,
    ) : TopologyBuildRejection
    data object WorkspaceMoved : TopologyBuildRejection
    data class PublicationFailed(
        val failure: TopologyPublicationRejection,
    ) : TopologyBuildRejection
}
