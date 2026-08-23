package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.TopologyBuildQualification
import io.github.amichne.kast.protocol.contract.TopologyBuildRejection
import io.github.amichne.kast.protocol.contract.TopologyBuildRequest
import io.github.amichne.kast.protocol.contract.TopologyBuildResult as ProtocolTopologyBuildResult
import io.github.amichne.kast.protocol.contract.TopologyBuildStatus
import io.github.amichne.kast.runtime.server.OperationHandler
import io.github.amichne.kast.topology.contract.TopologyBuildFailure
import io.github.amichne.kast.topology.contract.TopologyBuildOperations
import io.github.amichne.kast.topology.contract.TopologyBuildResult

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
            OperationOutcome.Rejected(TopologyBuildRejection.WORKSPACE_MOVED)
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
        val digest = when (val text = ProtocolText.parse(snapshot.manifest.digest.value)) {
            is Refinement.Refined -> text.value
            is Refinement.Rejected ->
                return OperationOutcome.Rejected(TopologyBuildRejection.PUBLICATION_FAILED)
        }
        return OperationOutcome.Complete(
            EvidenceEnvelope(
                CanonicalOperation.TOPOLOGY_BUILD.id,
                snapshot.identity.lease.generation,
                ProtocolTopologyBuildResult(status, digest),
            ),
        )
    }
}

private fun TopologyBuildFailure.toProtocol(): TopologyBuildRejection = when (this) {
    TopologyBuildFailure.WorkspaceNotReady -> TopologyBuildRejection.WORKSPACE_NOT_READY
    TopologyBuildFailure.SnapshotContractViolation,
    is TopologyBuildFailure.SnapshotRead,
        -> TopologyBuildRejection.SNAPSHOT_UNAVAILABLE
    is TopologyBuildFailure.Enumeration -> TopologyBuildRejection.ENUMERATION_FAILED
    is TopologyBuildFailure.Extraction,
    TopologyBuildFailure.ExtractionContractViolation,
        -> TopologyBuildRejection.EXTRACTION_FAILED
    is TopologyBuildFailure.Coverage -> TopologyBuildRejection.COVERAGE_INCOMPLETE
    is TopologyBuildFailure.Publication -> TopologyBuildRejection.PUBLICATION_FAILED
}
