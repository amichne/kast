package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.cli.CliOutcomeProjector
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.TopologyBuildQualification
import io.github.amichne.kast.protocol.contract.TopologyBuildRejection
import io.github.amichne.kast.protocol.contract.TopologyBuildResult
import io.github.amichne.kast.protocol.contract.TopologyBuildStatus
import io.github.amichne.kast.protocol.contract.TopologyCoverageProjectionRejection
import io.github.amichne.kast.protocol.contract.TopologyEnumerationRejection
import io.github.amichne.kast.protocol.contract.TopologyExtractionRejection
import io.github.amichne.kast.protocol.contract.TopologyPublicationRejection
import io.github.amichne.kast.protocol.contract.TopologySnapshotRejection
import kotlinx.serialization.Serializable

internal val topologyBuildCliProjector = CliOutcomeProjector<
    TopologyBuildResult,
    TopologyBuildQualification,
    TopologyBuildRejection,
    > { outcome ->
    projectClosedOutcome(
        outcome,
        complete = { result -> topologyCompleteFactory.create(result.completeDocument()) },
        qualified = { result, qualification ->
            topologyQualifiedFactory.create(
                TopologyBuildQualifiedCliDocument(
                    operation = CanonicalOperation.TOPOLOGY_BUILD.id.value,
                    status = "qualified",
                    snapshotStatus = result.status.wireName(),
                    generation = result.generation.value,
                    digest = result.digest.value,
                    qualification = qualification.wireName(),
                ),
            )
        },
        rejected = TopologyBuildRejection::rejectedDocument,
    )
}

@Serializable
private data class TopologyBuildCompleteCliDocument(
    val operation: String,
    val status: String,
    val snapshotStatus: String,
    val generation: Long,
    val digest: String,
)

@Serializable
private data class TopologyBuildQualifiedCliDocument(
    val operation: String,
    val status: String,
    val snapshotStatus: String,
    val generation: Long,
    val digest: String,
    val qualification: String,
)

@Serializable
private data class TopologyBuildRejectedCliDocument(
    val operation: String,
    val status: String,
    val reason: String,
)

@Serializable
private data class TopologyBuildFailureRejectedCliDocument(
    val operation: String,
    val status: String,
    val reason: String,
    val failure: String,
)

@Serializable
private data class TopologyBuildExtractionRejectedCliDocument(
    val operation: String,
    val status: String,
    val reason: String,
    val file: String,
    val failure: String,
)

private fun TopologyBuildResult.completeDocument(): TopologyBuildCompleteCliDocument =
    TopologyBuildCompleteCliDocument(
        operation = CanonicalOperation.TOPOLOGY_BUILD.id.value,
        status = "complete",
        snapshotStatus = status.wireName(),
        generation = generation.value,
        digest = digest.value,
    )

private fun TopologyBuildRejection.rejectedDocument(): CliJsonDocument =
    when (this) {
        TopologyBuildRejection.WorkspaceNotReady -> rejectedDocument("workspace-not-ready")
        is TopologyBuildRejection.SnapshotUnavailable -> failureRejectedDocument(
            "snapshot-unavailable",
            failure.wireName(),
        )
        is TopologyBuildRejection.EnumerationFailed -> failureRejectedDocument(
            "enumeration-failed",
            failure.wireName(),
        )
        is TopologyBuildRejection.ExtractionFailed -> extractionRejectedFactory.create(
            TopologyBuildExtractionRejectedCliDocument(
                operation = CanonicalOperation.TOPOLOGY_BUILD.id.value,
                status = "rejected",
                reason = "extraction-failed",
                file = file.value,
                failure = failure.wireName(),
            ),
        )
        TopologyBuildRejection.ExtractionContractViolation ->
            rejectedDocument("extraction-contract-violation")
        is TopologyBuildRejection.CoverageIncomplete -> coverageRejectedDocument()
        is TopologyBuildRejection.CoverageProjectionFailed -> failureRejectedDocument(
            "coverage-projection-failed",
            failure.wireName(),
        )
        TopologyBuildRejection.WorkspaceMoved -> rejectedDocument("workspace-moved")
        is TopologyBuildRejection.PublicationFailed -> failureRejectedDocument(
            "publication-failed",
            failure.wireName(),
        )
    }

private fun rejectedDocument(
    reason: String,
): CliJsonDocument = topologyRejectedFactory.create(
    TopologyBuildRejectedCliDocument(
        operation = CanonicalOperation.TOPOLOGY_BUILD.id.value,
        status = "rejected",
        reason = reason,
    ),
)

private fun failureRejectedDocument(
    reason: String,
    failure: String,
): CliJsonDocument = failureRejectedFactory.create(
    TopologyBuildFailureRejectedCliDocument(
        operation = CanonicalOperation.TOPOLOGY_BUILD.id.value,
        status = "rejected",
        reason = reason,
        failure = failure,
    ),
)

private val topologyCompleteFactory =
    CliJsonDocument.generated(TopologyBuildCompleteCliDocument.serializer())
private val topologyQualifiedFactory =
    CliJsonDocument.generated(TopologyBuildQualifiedCliDocument.serializer())
private val topologyRejectedFactory =
    CliJsonDocument.generated(TopologyBuildRejectedCliDocument.serializer())
private val failureRejectedFactory =
    CliJsonDocument.generated(TopologyBuildFailureRejectedCliDocument.serializer())
private val extractionRejectedFactory =
    CliJsonDocument.generated(TopologyBuildExtractionRejectedCliDocument.serializer())

private fun TopologyBuildStatus.wireName(): String = when (this) {
    TopologyBuildStatus.PUBLISHED -> "published"
    TopologyBuildStatus.REUSED -> "reused"
}

private fun TopologyBuildQualification.wireName(): String = when (this) {
    TopologyBuildQualification.PROGRESS_UNAVAILABLE -> "progress-unavailable"
}

private fun TopologySnapshotRejection.wireName(): String = when (this) {
    TopologySnapshotRejection.CONTRACT_VIOLATION -> "contract-violation"
    TopologySnapshotRejection.STORAGE_UNAVAILABLE -> "storage-unavailable"
    TopologySnapshotRejection.CORRUPT_SNAPSHOT -> "corrupt-snapshot"
}

private fun TopologyEnumerationRejection.wireName(): String = when (this) {
    TopologyEnumerationRejection.WORKSPACE_UNAVAILABLE -> "workspace-unavailable"
    TopologyEnumerationRejection.SOURCE_ROOT_UNAVAILABLE -> "source-root-unavailable"
    TopologyEnumerationRejection.SOURCE_CONTENT_UNAVAILABLE -> "source-content-unavailable"
    TopologyEnumerationRejection.AMBIGUOUS_SOURCE_ROOT_OWNER -> "ambiguous-source-root-owner"
    TopologyEnumerationRejection.CANDIDATE_REJECTED -> "candidate-rejected"
}

private fun TopologyExtractionRejection.wireName(): String = when (this) {
    TopologyExtractionRejection.PROJECT_UNAVAILABLE -> "project-unavailable"
    TopologyExtractionRejection.FILE_UNAVAILABLE -> "file-unavailable"
    TopologyExtractionRejection.DOCUMENT_DIRTY -> "document-dirty"
    TopologyExtractionRejection.PSI_DOCUMENT_UNCOMMITTED -> "psi-document-uncommitted"
    TopologyExtractionRejection.VFS_CONTENT_MISMATCH -> "vfs-content-mismatch"
    TopologyExtractionRejection.SOURCE_CONTENT_CHANGED_DURING_BUILD ->
        "source-content-changed-during-build"
    TopologyExtractionRejection.NOT_KOTLIN_PSI -> "not-kotlin-psi"
    TopologyExtractionRejection.COMPILER_UNAVAILABLE -> "compiler-unavailable"
    TopologyExtractionRejection.FACT_REJECTED -> "fact-rejected"
}

private fun TopologyPublicationRejection.wireName(): String = when (this) {
    TopologyPublicationRejection.CONTRACT_VIOLATION -> "contract-violation"
    TopologyPublicationRejection.STORAGE_UNAVAILABLE -> "storage-unavailable"
    TopologyPublicationRejection.SNAPSHOT_CONFLICT -> "snapshot-conflict"
    TopologyPublicationRejection.CORRUPT_SNAPSHOT -> "corrupt-snapshot"
}

private fun TopologyCoverageProjectionRejection.wireName(): String = when (this) {
    TopologyCoverageProjectionRejection.UNREPRESENTABLE_TEXT -> "unrepresentable-text"
    TopologyCoverageProjectionRejection.UNREPRESENTABLE_RANGE -> "unrepresentable-range"
    TopologyCoverageProjectionRejection.UNREPRESENTABLE_CONTENT_HASH ->
        "unrepresentable-content-hash"
    TopologyCoverageProjectionRejection.UNREPRESENTABLE_COMPILER_EVIDENCE ->
        "unrepresentable-compiler-evidence"
    TopologyCoverageProjectionRejection.EMPTY_FAILURE -> "empty-failure"
}
