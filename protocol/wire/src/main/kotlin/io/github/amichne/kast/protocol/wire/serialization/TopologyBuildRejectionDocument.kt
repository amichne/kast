@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.TopologyBuildDigest
import io.github.amichne.kast.protocol.contract.TopologyBuildRejection
import io.github.amichne.kast.protocol.contract.TopologyBuildQualification
import io.github.amichne.kast.protocol.contract.TopologyBuildResult
import io.github.amichne.kast.protocol.contract.TopologyBuildStatus
import io.github.amichne.kast.protocol.contract.TopologyEnumerationRejection
import io.github.amichne.kast.protocol.contract.TopologyExtractionRejection
import io.github.amichne.kast.protocol.contract.TopologyPublicationRejection
import io.github.amichne.kast.protocol.contract.TopologySnapshotRejection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
internal data object TopologyBuildRequestDocument

@Serializable
internal data class TopologyBuildResultDocument(
    val status: TopologyBuildStatusDocument,
    val generation: Long,
    val digest: String,
)

@Serializable
internal enum class TopologyBuildStatusDocument {
    @SerialName("published")
    PUBLISHED,

    @SerialName("reused")
    REUSED,
}

@Serializable
internal enum class TopologyBuildQualificationDocument {
    @SerialName("progress-unavailable")
    PROGRESS_UNAVAILABLE,
}

@Serializable
@JsonClassDiscriminator("reason")
internal sealed interface TopologyBuildRejectionDocument {
    @Serializable
    @SerialName("workspace-not-ready")
    data object WorkspaceNotReady : TopologyBuildRejectionDocument

    @Serializable
    @SerialName("snapshot-unavailable")
    data class SnapshotUnavailable(
        val failure: TopologySnapshotFailureDocument,
    ) : TopologyBuildRejectionDocument

    @Serializable
    @SerialName("enumeration-failed")
    data class EnumerationFailed(
        val failure: TopologyEnumerationFailureDocument,
    ) : TopologyBuildRejectionDocument

    @Serializable
    @SerialName("extraction-failed")
    data class ExtractionFailed(
        val file: String,
        val failure: TopologyExtractionFailureDocument,
    ) : TopologyBuildRejectionDocument

    @Serializable
    @SerialName("extraction-contract-violation")
    data object ExtractionContractViolation : TopologyBuildRejectionDocument

    @Serializable
    @SerialName("coverage-incomplete")
    data class CoverageIncomplete(
        val failure: TopologyCoverageFailureDocument,
    ) : TopologyBuildRejectionDocument

    @Serializable
    @SerialName("coverage-projection-failed")
    data class CoverageProjectionFailed(
        val failure: TopologyCoverageProjectionFailureDocument,
    ) : TopologyBuildRejectionDocument

    @Serializable
    @SerialName("workspace-moved")
    data object WorkspaceMoved : TopologyBuildRejectionDocument

    @Serializable
    @SerialName("publication-failed")
    data class PublicationFailed(
        val failure: TopologyPublicationFailureDocument,
    ) : TopologyBuildRejectionDocument
}

internal fun TopologyBuildResult.toSerializableDocument(): TopologyBuildResultDocument =
    TopologyBuildResultDocument(
        status = when (status) {
            TopologyBuildStatus.PUBLISHED -> TopologyBuildStatusDocument.PUBLISHED
            TopologyBuildStatus.REUSED -> TopologyBuildStatusDocument.REUSED
        },
        generation = generation.value,
        digest = digest.value,
    )

/**
 * Proof transition: `TopologyBuildResultDocument -> TopologyBuildResult`.
 *
 * Establishes refined evidence generation and exact lowercase SHA-256 digest values inside the
 * closed success variant. [WireDocumentConversion.Rejected] is the closed expected failure. Raw document
 * primitives are extracted only in this wire adapter.
 */
internal fun TopologyBuildResultDocument.toContract(): WireDocumentConversion<TopologyBuildResult> =
    combineConverted(
        EvidenceGeneration.parse(generation).toWireDocumentConversion(),
        TopologyBuildDigest.parse(digest).toWireDocumentConversion(),
    ) { generation, digest ->
        TopologyBuildResult(
            status = when (status) {
                TopologyBuildStatusDocument.PUBLISHED -> TopologyBuildStatus.PUBLISHED
                TopologyBuildStatusDocument.REUSED -> TopologyBuildStatus.REUSED
            },
            generation = generation,
            digest = digest,
        )
    }

internal fun TopologyBuildQualification.toSerializableDocument():
    TopologyBuildQualificationDocument = when (this) {
    TopologyBuildQualification.PROGRESS_UNAVAILABLE ->
        TopologyBuildQualificationDocument.PROGRESS_UNAVAILABLE
}

internal fun TopologyBuildQualificationDocument.toContract():
    WireDocumentConversion<TopologyBuildQualification> = WireDocumentConversion.Converted(
    when (this) {
        TopologyBuildQualificationDocument.PROGRESS_UNAVAILABLE ->
            TopologyBuildQualification.PROGRESS_UNAVAILABLE
    },
)

@Serializable
internal enum class TopologySnapshotFailureDocument {
    @SerialName("contract-violation")
    CONTRACT_VIOLATION,

    @SerialName("storage-unavailable")
    STORAGE_UNAVAILABLE,

    @SerialName("corrupt-snapshot")
    CORRUPT_SNAPSHOT,
}

@Serializable
internal enum class TopologyEnumerationFailureDocument {
    @SerialName("workspace-unavailable")
    WORKSPACE_UNAVAILABLE,

    @SerialName("source-root-unavailable")
    SOURCE_ROOT_UNAVAILABLE,

    @SerialName("source-content-unavailable")
    SOURCE_CONTENT_UNAVAILABLE,

    @SerialName("ambiguous-source-root-owner")
    AMBIGUOUS_SOURCE_ROOT_OWNER,

    @SerialName("candidate-rejected")
    CANDIDATE_REJECTED,
}

@Serializable
internal enum class TopologyExtractionFailureDocument {
    @SerialName("project-unavailable")
    PROJECT_UNAVAILABLE,

    @SerialName("file-unavailable")
    FILE_UNAVAILABLE,

    @SerialName("document-dirty")
    DOCUMENT_DIRTY,

    @SerialName("psi-document-uncommitted")
    PSI_DOCUMENT_UNCOMMITTED,

    @SerialName("vfs-content-mismatch")
    VFS_CONTENT_MISMATCH,

    @SerialName("source-content-changed-during-build")
    SOURCE_CONTENT_CHANGED_DURING_BUILD,

    @SerialName("not-kotlin-psi")
    NOT_KOTLIN_PSI,

    @SerialName("compiler-unavailable")
    COMPILER_UNAVAILABLE,

    @SerialName("fact-rejected")
    FACT_REJECTED,
}

@Serializable
internal enum class TopologyPublicationFailureDocument {
    @SerialName("contract-violation")
    CONTRACT_VIOLATION,

    @SerialName("storage-unavailable")
    STORAGE_UNAVAILABLE,

    @SerialName("snapshot-conflict")
    SNAPSHOT_CONFLICT,

    @SerialName("corrupt-snapshot")
    CORRUPT_SNAPSHOT,
}

internal fun TopologyBuildRejection.toSerializableDocument(): TopologyBuildRejectionDocument =
    when (this) {
        TopologyBuildRejection.WorkspaceNotReady ->
            TopologyBuildRejectionDocument.WorkspaceNotReady
        is TopologyBuildRejection.SnapshotUnavailable ->
            TopologyBuildRejectionDocument.SnapshotUnavailable(failure.toDocument())
        is TopologyBuildRejection.EnumerationFailed ->
            TopologyBuildRejectionDocument.EnumerationFailed(failure.toDocument())
        is TopologyBuildRejection.ExtractionFailed ->
            TopologyBuildRejectionDocument.ExtractionFailed(
                file.value,
                failure.toDocument(),
            )
        TopologyBuildRejection.ExtractionContractViolation ->
            TopologyBuildRejectionDocument.ExtractionContractViolation
        is TopologyBuildRejection.CoverageIncomplete ->
            TopologyBuildRejectionDocument.CoverageIncomplete(
                failure.toSerializableDocument(),
            )
        is TopologyBuildRejection.CoverageProjectionFailed ->
            TopologyBuildRejectionDocument.CoverageProjectionFailed(failure.toDocument())
        TopologyBuildRejection.WorkspaceMoved ->
            TopologyBuildRejectionDocument.WorkspaceMoved
        is TopologyBuildRejection.PublicationFailed ->
            TopologyBuildRejectionDocument.PublicationFailed(failure.toDocument())
    }

/**
 * Proof transition: `TopologyBuildRejectionDocument -> TopologyBuildRejection`.
 *
 * Establishes a closed public rejection, refined extraction path, and exact coverage evidence when
 * those variants are present. [WireDocumentConversion.Rejected] is the closed expected failure.
 * Raw document primitives are extracted only in this wire adapter.
 */
internal fun TopologyBuildRejectionDocument.toContract(): WireDocumentConversion<TopologyBuildRejection> =
    when (this) {
    TopologyBuildRejectionDocument.WorkspaceNotReady ->
        WireDocumentConversion.Converted(TopologyBuildRejection.WorkspaceNotReady)
    is TopologyBuildRejectionDocument.SnapshotUnavailable ->
        WireDocumentConversion.Converted(TopologyBuildRejection.SnapshotUnavailable(failure.toContract()))
    is TopologyBuildRejectionDocument.EnumerationFailed ->
        WireDocumentConversion.Converted(TopologyBuildRejection.EnumerationFailed(failure.toContract()))
    is TopologyBuildRejectionDocument.ExtractionFailed -> file.protocolText().mapConverted { file ->
        TopologyBuildRejection.ExtractionFailed(file, failure.toContract())
    }
    TopologyBuildRejectionDocument.ExtractionContractViolation ->
        WireDocumentConversion.Converted(TopologyBuildRejection.ExtractionContractViolation)
    is TopologyBuildRejectionDocument.CoverageIncomplete ->
        failure.toContract().mapConverted { failure ->
            TopologyBuildRejection.CoverageIncomplete(failure)
        }
    is TopologyBuildRejectionDocument.CoverageProjectionFailed ->
        WireDocumentConversion.Converted(
            TopologyBuildRejection.CoverageProjectionFailed(failure.toContract()),
        )
    TopologyBuildRejectionDocument.WorkspaceMoved ->
        WireDocumentConversion.Converted(TopologyBuildRejection.WorkspaceMoved)
    is TopologyBuildRejectionDocument.PublicationFailed ->
        WireDocumentConversion.Converted(TopologyBuildRejection.PublicationFailed(failure.toContract()))
}

/**
 * Proof transition: `String -> ProtocolText`.
 *
 * Establishes non-blank bounded extraction-path text. [WireDocumentConversion.Rejected] is the
 * closed expected failure. Raw text is admitted only from the generated rejection document.
 */
private fun String.protocolText(): WireDocumentConversion<ProtocolText> = ProtocolText.parse(this)
    .toWireDocumentConversion()

private fun TopologySnapshotRejection.toDocument(): TopologySnapshotFailureDocument = when (this) {
    TopologySnapshotRejection.CONTRACT_VIOLATION ->
        TopologySnapshotFailureDocument.CONTRACT_VIOLATION
    TopologySnapshotRejection.STORAGE_UNAVAILABLE ->
        TopologySnapshotFailureDocument.STORAGE_UNAVAILABLE
    TopologySnapshotRejection.CORRUPT_SNAPSHOT ->
        TopologySnapshotFailureDocument.CORRUPT_SNAPSHOT
}

private fun TopologySnapshotFailureDocument.toContract(): TopologySnapshotRejection = when (this) {
    TopologySnapshotFailureDocument.CONTRACT_VIOLATION ->
        TopologySnapshotRejection.CONTRACT_VIOLATION
    TopologySnapshotFailureDocument.STORAGE_UNAVAILABLE ->
        TopologySnapshotRejection.STORAGE_UNAVAILABLE
    TopologySnapshotFailureDocument.CORRUPT_SNAPSHOT ->
        TopologySnapshotRejection.CORRUPT_SNAPSHOT
}

private fun TopologyEnumerationRejection.toDocument(): TopologyEnumerationFailureDocument =
    when (this) {
        TopologyEnumerationRejection.WORKSPACE_UNAVAILABLE ->
            TopologyEnumerationFailureDocument.WORKSPACE_UNAVAILABLE
        TopologyEnumerationRejection.SOURCE_ROOT_UNAVAILABLE ->
            TopologyEnumerationFailureDocument.SOURCE_ROOT_UNAVAILABLE
        TopologyEnumerationRejection.SOURCE_CONTENT_UNAVAILABLE ->
            TopologyEnumerationFailureDocument.SOURCE_CONTENT_UNAVAILABLE
        TopologyEnumerationRejection.AMBIGUOUS_SOURCE_ROOT_OWNER ->
            TopologyEnumerationFailureDocument.AMBIGUOUS_SOURCE_ROOT_OWNER
        TopologyEnumerationRejection.CANDIDATE_REJECTED ->
            TopologyEnumerationFailureDocument.CANDIDATE_REJECTED
    }

private fun TopologyEnumerationFailureDocument.toContract(): TopologyEnumerationRejection =
    when (this) {
        TopologyEnumerationFailureDocument.WORKSPACE_UNAVAILABLE ->
            TopologyEnumerationRejection.WORKSPACE_UNAVAILABLE
        TopologyEnumerationFailureDocument.SOURCE_ROOT_UNAVAILABLE ->
            TopologyEnumerationRejection.SOURCE_ROOT_UNAVAILABLE
        TopologyEnumerationFailureDocument.SOURCE_CONTENT_UNAVAILABLE ->
            TopologyEnumerationRejection.SOURCE_CONTENT_UNAVAILABLE
        TopologyEnumerationFailureDocument.AMBIGUOUS_SOURCE_ROOT_OWNER ->
            TopologyEnumerationRejection.AMBIGUOUS_SOURCE_ROOT_OWNER
        TopologyEnumerationFailureDocument.CANDIDATE_REJECTED ->
            TopologyEnumerationRejection.CANDIDATE_REJECTED
    }

private fun TopologyExtractionRejection.toDocument(): TopologyExtractionFailureDocument =
    when (this) {
        TopologyExtractionRejection.PROJECT_UNAVAILABLE ->
            TopologyExtractionFailureDocument.PROJECT_UNAVAILABLE
        TopologyExtractionRejection.FILE_UNAVAILABLE ->
            TopologyExtractionFailureDocument.FILE_UNAVAILABLE
        TopologyExtractionRejection.DOCUMENT_DIRTY ->
            TopologyExtractionFailureDocument.DOCUMENT_DIRTY
        TopologyExtractionRejection.PSI_DOCUMENT_UNCOMMITTED ->
            TopologyExtractionFailureDocument.PSI_DOCUMENT_UNCOMMITTED
        TopologyExtractionRejection.VFS_CONTENT_MISMATCH ->
            TopologyExtractionFailureDocument.VFS_CONTENT_MISMATCH
        TopologyExtractionRejection.SOURCE_CONTENT_CHANGED_DURING_BUILD ->
            TopologyExtractionFailureDocument.SOURCE_CONTENT_CHANGED_DURING_BUILD
        TopologyExtractionRejection.NOT_KOTLIN_PSI ->
            TopologyExtractionFailureDocument.NOT_KOTLIN_PSI
        TopologyExtractionRejection.COMPILER_UNAVAILABLE ->
            TopologyExtractionFailureDocument.COMPILER_UNAVAILABLE
        TopologyExtractionRejection.FACT_REJECTED ->
            TopologyExtractionFailureDocument.FACT_REJECTED
    }

private fun TopologyExtractionFailureDocument.toContract(): TopologyExtractionRejection =
    when (this) {
        TopologyExtractionFailureDocument.PROJECT_UNAVAILABLE ->
            TopologyExtractionRejection.PROJECT_UNAVAILABLE
        TopologyExtractionFailureDocument.FILE_UNAVAILABLE ->
            TopologyExtractionRejection.FILE_UNAVAILABLE
        TopologyExtractionFailureDocument.DOCUMENT_DIRTY ->
            TopologyExtractionRejection.DOCUMENT_DIRTY
        TopologyExtractionFailureDocument.PSI_DOCUMENT_UNCOMMITTED ->
            TopologyExtractionRejection.PSI_DOCUMENT_UNCOMMITTED
        TopologyExtractionFailureDocument.VFS_CONTENT_MISMATCH ->
            TopologyExtractionRejection.VFS_CONTENT_MISMATCH
        TopologyExtractionFailureDocument.SOURCE_CONTENT_CHANGED_DURING_BUILD ->
            TopologyExtractionRejection.SOURCE_CONTENT_CHANGED_DURING_BUILD
        TopologyExtractionFailureDocument.NOT_KOTLIN_PSI ->
            TopologyExtractionRejection.NOT_KOTLIN_PSI
        TopologyExtractionFailureDocument.COMPILER_UNAVAILABLE ->
            TopologyExtractionRejection.COMPILER_UNAVAILABLE
        TopologyExtractionFailureDocument.FACT_REJECTED ->
            TopologyExtractionRejection.FACT_REJECTED
    }

private fun TopologyPublicationRejection.toDocument(): TopologyPublicationFailureDocument =
    when (this) {
        TopologyPublicationRejection.CONTRACT_VIOLATION ->
            TopologyPublicationFailureDocument.CONTRACT_VIOLATION
        TopologyPublicationRejection.STORAGE_UNAVAILABLE ->
            TopologyPublicationFailureDocument.STORAGE_UNAVAILABLE
        TopologyPublicationRejection.SNAPSHOT_CONFLICT ->
            TopologyPublicationFailureDocument.SNAPSHOT_CONFLICT
        TopologyPublicationRejection.CORRUPT_SNAPSHOT ->
            TopologyPublicationFailureDocument.CORRUPT_SNAPSHOT
    }

private fun TopologyPublicationFailureDocument.toContract(): TopologyPublicationRejection =
    when (this) {
        TopologyPublicationFailureDocument.CONTRACT_VIOLATION ->
            TopologyPublicationRejection.CONTRACT_VIOLATION
        TopologyPublicationFailureDocument.STORAGE_UNAVAILABLE ->
            TopologyPublicationRejection.STORAGE_UNAVAILABLE
        TopologyPublicationFailureDocument.SNAPSHOT_CONFLICT ->
            TopologyPublicationRejection.SNAPSHOT_CONFLICT
        TopologyPublicationFailureDocument.CORRUPT_SNAPSHOT ->
            TopologyPublicationRejection.CORRUPT_SNAPSHOT
    }
