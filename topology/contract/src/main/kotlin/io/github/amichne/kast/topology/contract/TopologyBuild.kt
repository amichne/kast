package io.github.amichne.kast.topology.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath

data class TopologyCandidateSetFailure(
    val duplicatePaths: Set<WorkspaceSourcePath>,
    val workspaceMismatches: Set<WorkspaceSourcePath>,
)

/** Deterministic unique Kotlin candidates for one exact workspace publication. */
class TopologyCandidateSet private constructor(
    val workspace: TopologyWorkspaceIdentity,
    val files: List<TopologySourceFile>,
) {
    companion object {
        /**
         * Proof transition: `(PublishedWorkspace, List<TopologySourceFile>) ->
         * Refinement<TopologyCandidateSet, TopologyCandidateSetFailure>`.
         *
         * Establishes unique path coverage, one exact workspace identity, and canonical file
         * ordering. [TopologyCandidateSetFailure] is the closed expected failure. Candidate lists
         * may enter only from the admitted-root enumeration adapter.
         */
        fun admit(
            workspace: PublishedWorkspace,
            files: List<TopologySourceFile>,
        ): Refinement<TopologyCandidateSet, TopologyCandidateSetFailure> {
            val identity = TopologyWorkspaceIdentity.from(workspace)
            val duplicatePaths = files.groupBy(TopologySourceFile::path)
                .filterValues { it.size > 1 }
                .keys
            val workspaceMismatches = files.filter { it.workspace != identity }
                .mapTo(linkedSetOf(), TopologySourceFile::path)
            val failure = TopologyCandidateSetFailure(duplicatePaths, workspaceMismatches)
            return if (duplicatePaths.isEmpty() && workspaceMismatches.isEmpty()) {
                Refinement.Refined(TopologyCandidateSet(identity, files.sorted()))
            } else {
                Refinement.Rejected(failure)
            }
        }
    }

    /**
     * Proof transition: `TopologySourceFile -> Refinement<TopologyExtractionRequest,
     * TopologyExtractionRequestFailure>`.
     *
     * Establishes that one exact candidate belongs to this complete candidate set. The closed
     * expected failure is [TopologyExtractionRequestFailure]. Raw file selection remains inside
     * the explicit build coordinator.
     */
    fun extractionRequest(
        file: TopologySourceFile,
    ): Refinement<TopologyExtractionRequest, TopologyExtractionRequestFailure> =
        if (files.binarySearch(file) >= 0) {
            Refinement.Refined(TopologyExtractionRequest(this, file))
        } else {
            Refinement.Rejected(TopologyExtractionRequestFailure.FILE_NOT_ADMITTED)
        }
}

enum class TopologyExtractionRequestFailure {
    FILE_NOT_ADMITTED,
}

/** Exact candidate plus complete admitted file set supplied to one K2 extraction call. */
class TopologyExtractionRequest internal constructor(
    val candidates: TopologyCandidateSet,
    val file: TopologySourceFile,
)

enum class TopologyCandidateEnumerationFailure {
    WORKSPACE_UNAVAILABLE,
    SOURCE_ROOT_UNAVAILABLE,
    SOURCE_CONTENT_UNAVAILABLE,
    AMBIGUOUS_SOURCE_ROOT_OWNER,
    CANDIDATE_REJECTED,
}

sealed interface TopologyCandidateEnumeration {
    data class Complete(
        val candidates: TopologyCandidateSet,
    ) : TopologyCandidateEnumeration

    data class Rejected(
        val failure: TopologyCandidateEnumerationFailure,
    ) : TopologyCandidateEnumeration
}

/** Physical enumeration boundary restricted to one already published source-root set. */
fun interface TopologyCandidateEnumerator {
    /**
     * Proof transition: `PublishedWorkspace -> TopologyCandidateEnumeration`.
     *
     * Complete output establishes deterministic Kotlin files from only the workspace's admitted
     * source roots. [TopologyCandidateEnumerationFailure] is the closed expected failure. VFS and
     * content hashing values remain inside the physical adapter.
     */
    fun enumerate(workspace: PublishedWorkspace): TopologyCandidateEnumeration
}

enum class TopologyExtractionFailure {
    PROJECT_UNAVAILABLE,
    FILE_UNAVAILABLE,
    NOT_KOTLIN_PSI,
    COMPILER_UNAVAILABLE,
    FACT_REJECTED,
}

sealed interface TopologyFileExtraction {
    data class Complete(
        val file: CompleteTopologyFile,
    ) : TopologyFileExtraction

    data class Failed(
        val failure: TopologyExtractionFailure,
    ) : TopologyFileExtraction
}

/** Explicit request-local K2 extraction boundary. */
fun interface TopologyFileExtractor {
    /**
     * Proof transition: `TopologyExtractionRequest -> TopologyFileExtraction`.
     *
     * Complete output establishes terminal detached K2 facts for the exact admitted file.
     * [TopologyExtractionFailure] is the closed expected failure. Live project, PSI, and K2 values
     * remain inside the adapter and cancellation propagates.
     */
    suspend fun extract(request: TopologyExtractionRequest): TopologyFileExtraction
}

sealed interface TopologyBuildFailure {
    data object WorkspaceNotReady : TopologyBuildFailure
    data object SnapshotContractViolation : TopologyBuildFailure
    data class SnapshotRead(val failure: TopologySnapshotReadFailure) : TopologyBuildFailure
    data class Enumeration(val failure: TopologyCandidateEnumerationFailure) : TopologyBuildFailure
    data class Extraction(
        val file: WorkspaceSourcePath,
        val failure: TopologyExtractionFailure,
    ) : TopologyBuildFailure

    data object ExtractionContractViolation : TopologyBuildFailure
    data class Coverage(val failure: TopologyGenerationCoverageFailure) : TopologyBuildFailure
    data class Publication(val failure: TopologyPublicationFailure) : TopologyBuildFailure
}

sealed interface TopologyBuildResult {
    data class Published(val snapshot: PublishedTopologySnapshot) : TopologyBuildResult
    data class Reused(val snapshot: PublishedTopologySnapshot) : TopologyBuildResult
    data object WorkspaceMoved : TopologyBuildResult
    data class Rejected(val failure: TopologyBuildFailure) : TopologyBuildResult
}

/** Sole public explicit `topology.build` service boundary. */
fun interface TopologyBuildOperations {
    suspend fun build(): TopologyBuildResult
}
