package io.github.amichne.kast.runtime.composition.protocol.graph

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.TopologyBuildDigest
import io.github.amichne.kast.protocol.contract.TopologyBuildQualification
import io.github.amichne.kast.protocol.contract.TopologyBuildRejection
import io.github.amichne.kast.protocol.contract.TopologyBuildRequest
import io.github.amichne.kast.protocol.contract.TopologyBuildResult as ProtocolTopologyBuildResult
import io.github.amichne.kast.protocol.contract.TopologyBuildStatus
import io.github.amichne.kast.protocol.contract.TopologyEnumerationRejection
import io.github.amichne.kast.protocol.contract.TopologyExtractionRejection
import io.github.amichne.kast.protocol.contract.TopologyPublicationRejection
import io.github.amichne.kast.protocol.contract.TopologySnapshotRejection
import io.github.amichne.kast.runtime.server.OperationHandler
import io.github.amichne.kast.runtime.server.toProtocolCoverage
import io.github.amichne.kast.topology.contract.TopologyBuildFailure
import io.github.amichne.kast.topology.contract.TopologyBuildOperations
import io.github.amichne.kast.topology.contract.TopologyBuildResult
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumerationFailure
import io.github.amichne.kast.topology.contract.TopologyExtractionFailure
import io.github.amichne.kast.topology.contract.TopologyPublicationFailure

/** Canonical protocol projection for the explicit topology build boundary. */
internal class CanonicalTopologyBuildHandler(
    private val operations: TopologyBuildOperations,
) : OperationHandler<
    TopologyBuildRequest,
    ProtocolTopologyBuildResult,
    TopologyBuildQualification,
    TopologyBuildRejection,
    > {
    override suspend fun execute(request: TopologyBuildRequest) = when (val result = operations.build()) {
        is TopologyBuildResult.Published -> complete(
            result.snapshot,
            TopologyBuildStatus.PUBLISHED,
        )
        is TopologyBuildResult.Reused -> complete(result.snapshot, TopologyBuildStatus.REUSED)
        TopologyBuildResult.WorkspaceMoved ->
            OperationOutcome.Rejected(TopologyBuildRejection.WorkspaceMoved)
        is TopologyBuildResult.Rejected -> OperationOutcome.Rejected(result.failure.toProtocol())
    }

    private fun complete(
        snapshot: io.github.amichne.kast.topology.contract.PublishedTopologySnapshot,
        status: TopologyBuildStatus,
    ): OperationOutcome<
        ProtocolTopologyBuildResult,
        TopologyBuildQualification,
        TopologyBuildRejection,
        > {
        val digest = when (val text = TopologyBuildDigest.parse(snapshot.manifest.digest.value)) {
            is Refinement.Refined -> text.value
            is Refinement.Rejected ->
                return OperationOutcome.Rejected(
                    TopologyBuildRejection.PublicationFailed(
                        TopologyPublicationRejection.CONTRACT_VIOLATION,
                    ),
                )
        }
        return OperationOutcome.Complete(
            EvidenceEnvelope(
                CanonicalOperation.TOPOLOGY_BUILD.id,
                snapshot.identity.lease.generation,
                ProtocolTopologyBuildResult(
                    status,
                    snapshot.identity.lease.generation,
                    digest,
                ),
            ),
        )
    }
}

private fun TopologyBuildFailure.toProtocol(): TopologyBuildRejection = when (this) {
    TopologyBuildFailure.WorkspaceNotReady -> TopologyBuildRejection.WorkspaceNotReady
    TopologyBuildFailure.SnapshotContractViolation -> TopologyBuildRejection.SnapshotUnavailable(
        TopologySnapshotRejection.CONTRACT_VIOLATION,
    )
    is TopologyBuildFailure.SnapshotRead -> TopologyBuildRejection.SnapshotUnavailable(
        when (failure) {
            io.github.amichne.kast.topology.contract.TopologySnapshotReadFailure.STORAGE_UNAVAILABLE ->
                TopologySnapshotRejection.STORAGE_UNAVAILABLE
            io.github.amichne.kast.topology.contract.TopologySnapshotReadFailure.CORRUPT_SNAPSHOT ->
                TopologySnapshotRejection.CORRUPT_SNAPSHOT
        },
    )
    is TopologyBuildFailure.Enumeration -> TopologyBuildRejection.EnumerationFailed(
        failure.toProtocol(),
    )
    is TopologyBuildFailure.Extraction -> when (val parsed = ProtocolText.parse(file.value)) {
        is Refinement.Refined -> TopologyBuildRejection.ExtractionFailed(
            parsed.value,
            failure.toProtocol(),
        )
        is Refinement.Rejected -> TopologyBuildRejection.ExtractionContractViolation
    }
    TopologyBuildFailure.ExtractionContractViolation ->
        TopologyBuildRejection.ExtractionContractViolation
    is TopologyBuildFailure.Coverage -> when (val projected = failure.toProtocolCoverage()) {
        is Refinement.Refined -> TopologyBuildRejection.CoverageIncomplete(projected.value)
        is Refinement.Rejected -> TopologyBuildRejection.CoverageProjectionFailed(
            projected.failure,
        )
    }
    is TopologyBuildFailure.Publication -> TopologyBuildRejection.PublicationFailed(
        failure.toProtocol(),
    )
}

private fun TopologyCandidateEnumerationFailure.toProtocol(): TopologyEnumerationRejection =
    when (this) {
        TopologyCandidateEnumerationFailure.WORKSPACE_UNAVAILABLE ->
            TopologyEnumerationRejection.WORKSPACE_UNAVAILABLE
        TopologyCandidateEnumerationFailure.SOURCE_ROOT_UNAVAILABLE ->
            TopologyEnumerationRejection.SOURCE_ROOT_UNAVAILABLE
        TopologyCandidateEnumerationFailure.SOURCE_CONTENT_UNAVAILABLE ->
            TopologyEnumerationRejection.SOURCE_CONTENT_UNAVAILABLE
        TopologyCandidateEnumerationFailure.AMBIGUOUS_SOURCE_ROOT_OWNER ->
            TopologyEnumerationRejection.AMBIGUOUS_SOURCE_ROOT_OWNER
        TopologyCandidateEnumerationFailure.CANDIDATE_REJECTED ->
            TopologyEnumerationRejection.CANDIDATE_REJECTED
    }

private fun TopologyExtractionFailure.toProtocol(): TopologyExtractionRejection = when (this) {
    TopologyExtractionFailure.PROJECT_UNAVAILABLE ->
        TopologyExtractionRejection.PROJECT_UNAVAILABLE
    TopologyExtractionFailure.FILE_UNAVAILABLE -> TopologyExtractionRejection.FILE_UNAVAILABLE
    TopologyExtractionFailure.DOCUMENT_DIRTY -> TopologyExtractionRejection.DOCUMENT_DIRTY
    TopologyExtractionFailure.PSI_DOCUMENT_UNCOMMITTED ->
        TopologyExtractionRejection.PSI_DOCUMENT_UNCOMMITTED
    TopologyExtractionFailure.VFS_CONTENT_MISMATCH ->
        TopologyExtractionRejection.VFS_CONTENT_MISMATCH
    TopologyExtractionFailure.SOURCE_CONTENT_CHANGED_DURING_BUILD ->
        TopologyExtractionRejection.SOURCE_CONTENT_CHANGED_DURING_BUILD
    TopologyExtractionFailure.NOT_KOTLIN_PSI -> TopologyExtractionRejection.NOT_KOTLIN_PSI
    TopologyExtractionFailure.COMPILER_UNAVAILABLE ->
        TopologyExtractionRejection.COMPILER_UNAVAILABLE
    TopologyExtractionFailure.DECLARATION_EVIDENCE_REJECTED ->
        TopologyExtractionRejection.DECLARATION_EVIDENCE_REJECTED
    TopologyExtractionFailure.PROJECTION_REGISTRY_REJECTED ->
        TopologyExtractionRejection.PROJECTION_REGISTRY_REJECTED
    TopologyExtractionFailure.COMPILER_IDENTITY_MISMATCH ->
        TopologyExtractionRejection.COMPILER_IDENTITY_MISMATCH
    TopologyExtractionFailure.REFERENCE_TARGET_REJECTED ->
        TopologyExtractionRejection.REFERENCE_TARGET_REJECTED
    TopologyExtractionFailure.OCCURRENCE_REJECTED ->
        TopologyExtractionRejection.OCCURRENCE_REJECTED
    TopologyExtractionFailure.EDGE_REJECTED -> TopologyExtractionRejection.EDGE_REJECTED
    TopologyExtractionFailure.OVERRIDE_REJECTED ->
        TopologyExtractionRejection.OVERRIDE_REJECTED
    TopologyExtractionFailure.FILE_ADMISSION_REJECTED ->
        TopologyExtractionRejection.FILE_ADMISSION_REJECTED
}

private fun TopologyPublicationFailure.toProtocol(): TopologyPublicationRejection = when (this) {
    TopologyPublicationFailure.STORAGE_UNAVAILABLE ->
        TopologyPublicationRejection.STORAGE_UNAVAILABLE
    TopologyPublicationFailure.SNAPSHOT_CONFLICT ->
        TopologyPublicationRejection.SNAPSHOT_CONFLICT
    TopologyPublicationFailure.CORRUPT_SNAPSHOT ->
        TopologyPublicationRejection.CORRUPT_SNAPSHOT
}
