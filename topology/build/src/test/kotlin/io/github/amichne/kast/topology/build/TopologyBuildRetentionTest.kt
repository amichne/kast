package io.github.amichne.kast.topology.build

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.CompleteTopologyFile
import io.github.amichne.kast.topology.contract.CompleteTopologyGeneration
import io.github.amichne.kast.topology.contract.PublishedTopologySnapshot
import io.github.amichne.kast.topology.contract.TopologyBuildResult
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumeration
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumerator
import io.github.amichne.kast.topology.contract.TopologyCandidateSet
import io.github.amichne.kast.topology.contract.TopologyFileExtraction
import io.github.amichne.kast.topology.contract.TopologyFileExtractor
import io.github.amichne.kast.topology.contract.TopologyPublicationResult
import io.github.amichne.kast.topology.contract.TopologySnapshotContentRead
import io.github.amichne.kast.topology.contract.TopologySnapshotEligibility
import io.github.amichne.kast.topology.contract.TopologySnapshotManifest
import io.github.amichne.kast.topology.contract.TopologySnapshotReadFailure
import io.github.amichne.kast.topology.contract.TopologySnapshotStore
import io.github.amichne.kast.topology.contract.TopologySourceFile
import io.github.amichne.kast.topology.contract.TopologyWorkspaceIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.GradleSourceRootEvidence
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.SemanticReadLeaseGuard
import io.github.amichne.kast.workspace.contract.SemanticReadLeaseUse
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class TopologyBuildRetentionTest {
    @Test
    fun `successful publication is retained for an exact repeat build`() = runTest {
        val fixture = retentionFixture()
        val eligibilityCalls = AtomicInteger()
        val enumerationCalls = AtomicInteger()
        val extractionCalls = AtomicInteger()
        val publicationCalls = AtomicInteger()
        val snapshots = object : TopologySnapshotStore {
            override fun eligible(identity: TopologyWorkspaceIdentity): TopologySnapshotEligibility {
                eligibilityCalls.incrementAndGet()
                return TopologySnapshotEligibility.Unavailable
            }

            override fun read(snapshot: PublishedTopologySnapshot) =
                TopologySnapshotContentRead.Rejected(
                    TopologySnapshotReadFailure.STORAGE_UNAVAILABLE,
                )

            override fun publish(generation: CompleteTopologyGeneration): TopologyPublicationResult {
                publicationCalls.incrementAndGet()
                return TopologyPublicationResult.Published(fixture.snapshot)
            }
        }
        val service = TopologyBuildService.create(
            WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(fixture.workspace) },
            RetentionCurrentGuard(fixture.workspace.readLease),
            TopologyCandidateEnumerator {
                enumerationCalls.incrementAndGet()
                TopologyCandidateEnumeration.Complete(fixture.candidates)
            },
            TopologyFileExtractor {
                extractionCalls.incrementAndGet()
                TopologyFileExtraction.Complete(fixture.complete)
            },
            snapshots,
        )

        val first = service.build()
        val second = service.build()

        assertEquals(TopologyBuildResult.Published(fixture.snapshot), first)
        assertEquals(TopologyBuildResult.Reused(fixture.snapshot), second)
        assertEquals(1, eligibilityCalls.get())
        assertEquals(1, enumerationCalls.get())
        assertEquals(1, extractionCalls.get())
        assertEquals(1, publicationCalls.get())
    }

    @Test
    fun `retained publication cannot satisfy a moved workspace identity`() = runTest {
        val first = retentionFixture()
        val moved = retentionFixture("moved-workspace-state", 8)
        var current = first.workspace
        val eligibilityCalls = AtomicInteger()
        val snapshots = object : TopologySnapshotStore {
            override fun eligible(identity: TopologyWorkspaceIdentity): TopologySnapshotEligibility {
                eligibilityCalls.incrementAndGet()
                return if (identity == moved.snapshot.identity) {
                    TopologySnapshotEligibility.Eligible(moved.snapshot)
                } else {
                    TopologySnapshotEligibility.Unavailable
                }
            }

            override fun read(snapshot: PublishedTopologySnapshot) =
                TopologySnapshotContentRead.Rejected(
                    TopologySnapshotReadFailure.STORAGE_UNAVAILABLE,
                )

            override fun publish(generation: CompleteTopologyGeneration) =
                TopologyPublicationResult.Published(first.snapshot)
        }
        val service = TopologyBuildService.create(
            WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(current) },
            object : SemanticReadLeaseGuard {
                override fun <Value> whileCurrent(
                    expected: SemanticReadLease,
                    operation: () -> Value,
                ): SemanticReadLeaseUse<Value> = SemanticReadLeaseUse.Completed(operation())
            },
            TopologyCandidateEnumerator {
                TopologyCandidateEnumeration.Complete(first.candidates)
            },
            TopologyFileExtractor {
                TopologyFileExtraction.Complete(first.complete)
            },
            snapshots,
        )

        assertEquals(TopologyBuildResult.Published(first.snapshot), service.build())
        current = moved.workspace
        assertEquals(TopologyBuildResult.Reused(moved.snapshot), service.build())
        assertEquals(2, eligibilityCalls.get())
    }
}

private fun retentionFixture(
    state: String = "workspace-state",
    generation: Long = 7,
): RetentionFixture {
    val sourceRoot = SourceRoot.admit(
        GradleSourceRootEvidence(
            "alpha.main",
            ".",
            ":alpha",
            "main",
            "alpha/src/main/kotlin",
            SourceRootProvenance.Authored,
        ),
    ).retentionRefined()
    val candidate = WorkspaceCandidate(
        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).retentionRefined(),
        WorkspaceStateIdentity.parse(state).retentionRefined(),
    )
    val workspace = PublishedWorkspace.publish(
        ReconciledWorkspace.admit(
            candidate,
            WorkspaceEvidenceKind.entries.toSet(),
            listOf(sourceRoot),
        ).retentionRefined(),
        EvidenceGeneration.parse(generation).retentionRefined(),
    )
    val file = TopologySourceFile.admit(
        workspace,
        sourceRoot,
        WorkspaceSourcePath.parse("alpha/src/main/kotlin/Alpha.kt").retentionRefined(),
        WorkspaceSourceContentHash.parse("a".repeat(64)).retentionRefined(),
    ).retentionRefined()
    val candidates = TopologyCandidateSet.admit(workspace, listOf(file)).retentionRefined()
    val complete = CompleteTopologyFile.admit(file, emptyList(), emptyList()).retentionRefined()
    val generation = CompleteTopologyGeneration.admit(
        workspace,
        candidates.files,
        listOf(complete),
    ).retentionRefined()
    return RetentionFixture(
        workspace,
        candidates,
        complete,
        RetentionSnapshot(generation.identity, TopologySnapshotManifest.from(generation)),
    )
}

private data class RetentionFixture(
    val workspace: PublishedWorkspace,
    val candidates: TopologyCandidateSet,
    val complete: CompleteTopologyFile,
    val snapshot: PublishedTopologySnapshot,
)

private data class RetentionSnapshot(
    override val identity: TopologyWorkspaceIdentity,
    override val manifest: TopologySnapshotManifest,
) : PublishedTopologySnapshot

private class RetentionCurrentGuard(
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

private fun <Value, Failure> Refinement<Value, Failure>.retentionRefined(): Value = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error(failure.toString())
}
