package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.EvidenceGeneration

/** Explicit request for the one generation-bound repository-topology build. */
data object TopologyBuildRequest : OperationRequest

enum class TopologyBuildStatus {
    PUBLISHED,
    REUSED,
}

/** Successful topology snapshot identity; only complete or exactly reusable builds produce it. */
data class TopologyBuildResult(
    val status: TopologyBuildStatus,
    val generation: EvidenceGeneration,
    val digest: ProtocolText,
) : OperationResult

/** Reserved progress state; synchronous canonical execution never returns qualified topology. */
enum class TopologyBuildQualification : OperationQualification {
    PROGRESS_UNAVAILABLE,
}

/** Closed public projection of every expected topology build failure. */
enum class TopologyBuildRejection : OperationRejection {
    WORKSPACE_NOT_READY,
    SNAPSHOT_UNAVAILABLE,
    ENUMERATION_FAILED,
    EXTRACTION_FAILED,
    COVERAGE_INCOMPLETE,
    WORKSPACE_MOVED,
    PUBLICATION_FAILED,
}
