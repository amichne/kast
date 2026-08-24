package io.github.amichne.kast.topology.build

import io.github.amichne.kast.topology.contract.CompleteTopologyFile
import io.github.amichne.kast.topology.contract.CompleteTopologyGeneration
import io.github.amichne.kast.topology.contract.PublishedTopologySnapshot
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumeration
import io.github.amichne.kast.topology.contract.TopologyPublicationResult
import io.github.amichne.kast.topology.contract.TopologySnapshotContentRead
import io.github.amichne.kast.topology.contract.TopologySnapshotEligibility
import io.github.amichne.kast.topology.contract.TopologySnapshotManifest
import io.github.amichne.kast.topology.contract.TopologySnapshotReadFailure
import io.github.amichne.kast.topology.contract.TopologySnapshotStore
import io.github.amichne.kast.topology.contract.TopologyWorkspaceIdentity
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.SemanticReadLeaseGuard
import io.github.amichne.kast.workspace.contract.SemanticReadLeaseUse
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import java.util.concurrent.atomic.AtomicInteger

internal data class Fixture(
    val workspace: PublishedWorkspace,
    val enumeration: TopologyCandidateEnumeration.Complete,
    val complete: CompleteTopologyFile,
    val generation: CompleteTopologyGeneration,
) {
    fun snapshot(): PublishedTopologySnapshot = TestSnapshot(
        generation.identity,
        TopologySnapshotManifest.from(generation),
    )
}

internal data class TestSnapshot(
    override val identity: TopologyWorkspaceIdentity,
    override val manifest: TopologySnapshotManifest,
) : PublishedTopologySnapshot

internal class FixedSnapshots(
    private val eligibility: TopologySnapshotEligibility,
    private val publicationCalls: AtomicInteger,
    private val snapshot: PublishedTopologySnapshot,
) : TopologySnapshotStore {
    override fun eligible(identity: TopologyWorkspaceIdentity): TopologySnapshotEligibility =
        eligibility

    override fun read(snapshot: PublishedTopologySnapshot): TopologySnapshotContentRead =
        TopologySnapshotContentRead.Rejected(TopologySnapshotReadFailure.STORAGE_UNAVAILABLE)

    override fun publish(
        generation: CompleteTopologyGeneration,
    ): TopologyPublicationResult {
        publicationCalls.incrementAndGet()
        return TopologyPublicationResult.Published(snapshot)
    }
}

internal class CurrentGuard(
    private val current: SemanticReadLease,
) : SemanticReadLeaseGuard {
    override fun <Value> whileCurrent(
        expected: SemanticReadLease,
        operation: () -> Value,
    ): SemanticReadLeaseUse<Value> = if (expected == current) {
        SemanticReadLeaseUse.Completed(operation())
    } else {
        SemanticReadLeaseUse.Moved
    }
}

internal data object MovedGuard : SemanticReadLeaseGuard {
    override fun <Value> whileCurrent(
        expected: SemanticReadLease,
        operation: () -> Value,
    ): SemanticReadLeaseUse<Value> = SemanticReadLeaseUse.Moved
}

internal fun ready(workspace: PublishedWorkspace): WorkspaceInspectionOperations =
    WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(workspace) }
