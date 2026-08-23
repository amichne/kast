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
import io.github.amichne.kast.topology.contract.TopologyExtractionFailure
import io.github.amichne.kast.topology.contract.TopologyExtractionRequest
import io.github.amichne.kast.topology.contract.TopologyFileExtraction
import io.github.amichne.kast.topology.contract.TopologyFileExtractor
import io.github.amichne.kast.topology.contract.TopologyPublicationResult
import io.github.amichne.kast.topology.contract.TopologySnapshotContent
import io.github.amichne.kast.topology.contract.TopologySnapshotContentRead
import io.github.amichne.kast.topology.contract.TopologySnapshotEligibility
import io.github.amichne.kast.topology.contract.TopologySnapshotManifest
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
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

class TopologyBuildServiceTest {
    @Test
    fun `matching published snapshot is reused before enumeration or extraction`() = runTest {
        val fixture = fixture()
        val snapshot = fixture.snapshot()
        val enumerationCalls = AtomicInteger()
        val extractionCalls = AtomicInteger()
        val publicationCalls = AtomicInteger()
        val service = TopologyBuildService.create(
            ready(fixture.workspace),
            CurrentGuard(fixture.workspace.readLease),
            TopologyCandidateEnumerator {
                enumerationCalls.incrementAndGet()
                fixture.enumeration
            },
            TopologyFileExtractor {
                extractionCalls.incrementAndGet()
                TopologyFileExtraction.Complete(fixture.complete)
            },
            FixedSnapshots(
                TopologySnapshotEligibility.Eligible(snapshot),
                publicationCalls,
                snapshot,
            ),
        )

        val result = service.build()

        assertEquals(TopologyBuildResult.Reused(snapshot), result)
        assertEquals(0, enumerationCalls.get())
        assertEquals(0, extractionCalls.get())
        assertEquals(0, publicationCalls.get())
    }

    @Test
    fun `unchanged files rebind stale snapshot to current lease without K2 extraction`() = runTest {
        val prior = fixture()
        val priorSnapshot = prior.snapshot()
        val priorContent = TopologySnapshotContent.admit(
            priorSnapshot,
            listOf(prior.complete),
        ).refined()
        val currentWorkspace = workspace(
            prior.complete.file.sourceRoot,
            "restarted-workspace-state",
            8,
        )
        val currentFile = TopologySourceFile.admit(
            currentWorkspace,
            prior.complete.file.sourceRoot,
            prior.complete.file.path,
            prior.complete.file.contentHash,
        ).refined()
        val currentCandidates = TopologyCandidateSet.admit(
            currentWorkspace,
            listOf(currentFile),
        ).refined()
        val extractionCalls = AtomicInteger()
        val publicationCalls = AtomicInteger()
        val snapshots = object : TopologySnapshotStore {
            override fun eligible(identity: TopologyWorkspaceIdentity) =
                TopologySnapshotEligibility.Stale(priorSnapshot)

            override fun read(snapshot: PublishedTopologySnapshot) =
                TopologySnapshotContentRead.Loaded(priorContent)

            override fun publish(generation: CompleteTopologyGeneration): TopologyPublicationResult {
                publicationCalls.incrementAndGet()
                return TopologyPublicationResult.Published(
                    TestSnapshot(generation.identity, TopologySnapshotManifest.from(generation)),
                )
            }
        }
        val service = TopologyBuildService.create(
            ready(currentWorkspace),
            CurrentGuard(currentWorkspace.readLease),
            TopologyCandidateEnumerator {
                TopologyCandidateEnumeration.Complete(currentCandidates)
            },
            TopologyFileExtractor {
                extractionCalls.incrementAndGet()
                TopologyFileExtraction.Failed(TopologyExtractionFailure.COMPILER_UNAVAILABLE)
            },
            snapshots,
        )

        val result = service.build()

        val reused = assertInstanceOf(TopologyBuildResult.Reused::class.java, result)
        assertEquals(TopologyWorkspaceIdentity.from(currentWorkspace), reused.snapshot.identity)
        assertEquals(0, extractionCalls.get())
        assertEquals(1, publicationCalls.get())
    }

    @Test
    fun `source edit cannot reuse stale compiler facts`() {
        val prior = fixture()
        val currentWorkspace = workspace(prior.complete.file.sourceRoot, "edited-state", 8)
        val editedFile = TopologySourceFile.admit(
            currentWorkspace,
            prior.complete.file.sourceRoot,
            prior.complete.file.path,
            WorkspaceSourceContentHash.parse("b".repeat(64)).refined(),
        ).refined()
        val candidates = TopologyCandidateSet.admit(currentWorkspace, listOf(editedFile)).refined()
        val snapshot = prior.snapshot()
        val content = TopologySnapshotContent.admit(snapshot, listOf(prior.complete)).refined()

        assertEquals(
            TopologyGenerationReuse.SourceChanged,
            rebindUnchangedTopologyGeneration(currentWorkspace, candidates, content),
        )
    }

    @Test
    fun `failed extraction makes publication unreachable`() = runTest {
        val fixture = fixture()
        val publicationCalls = AtomicInteger()
        val service = TopologyBuildService.create(
            ready(fixture.workspace),
            CurrentGuard(fixture.workspace.readLease),
            TopologyCandidateEnumerator { fixture.enumeration },
            TopologyFileExtractor {
                TopologyFileExtraction.Failed(TopologyExtractionFailure.COMPILER_UNAVAILABLE)
            },
            FixedSnapshots(
                TopologySnapshotEligibility.Unavailable,
                publicationCalls,
                fixture.snapshot(),
            ),
        )

        val result = service.build()

        assertInstanceOf(TopologyBuildResult.Rejected::class.java, result)
        assertEquals(0, publicationCalls.get())
    }

    @Test
    fun `cancelled extraction propagates and makes publication unreachable`() {
        val fixture = fixture()
        val publicationCalls = AtomicInteger()
        val service = TopologyBuildService.create(
            ready(fixture.workspace),
            CurrentGuard(fixture.workspace.readLease),
            TopologyCandidateEnumerator { fixture.enumeration },
            TopologyFileExtractor { throw CancellationException("cancelled") },
            FixedSnapshots(
                TopologySnapshotEligibility.Unavailable,
                publicationCalls,
                fixture.snapshot(),
            ),
        )

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.test.runTest { service.build() }
        }
        assertEquals(0, publicationCalls.get())
    }

    @Test
    fun `moved workspace prevents the complete generation from reaching publication`() = runTest {
        val fixture = fixture()
        val publicationCalls = AtomicInteger()
        val service = TopologyBuildService.create(
            ready(fixture.workspace),
            MovedGuard,
            TopologyCandidateEnumerator { fixture.enumeration },
            TopologyFileExtractor { TopologyFileExtraction.Complete(fixture.complete) },
            FixedSnapshots(
                TopologySnapshotEligibility.Unavailable,
                publicationCalls,
                fixture.snapshot(),
            ),
        )

        val result = service.build()

        assertEquals(TopologyBuildResult.WorkspaceMoved, result)
        assertEquals(0, publicationCalls.get())
    }

    @Test
    fun `complete stable generation publishes once`() = runTest {
        val fixture = fixture()
        val snapshot = fixture.snapshot()
        val publicationCalls = AtomicInteger()
        val service = TopologyBuildService.create(
            ready(fixture.workspace),
            CurrentGuard(fixture.workspace.readLease),
            TopologyCandidateEnumerator { fixture.enumeration },
            TopologyFileExtractor { TopologyFileExtraction.Complete(fixture.complete) },
            FixedSnapshots(
                TopologySnapshotEligibility.Unavailable,
                publicationCalls,
                snapshot,
            ),
        )

        val result = service.build()

        assertEquals(TopologyBuildResult.Published(snapshot), result)
        assertEquals(1, publicationCalls.get())
    }

    private fun fixture(): Fixture {
        val sourceRoot = sourceRoot()
        val workspace = workspace(sourceRoot)
        val file = TopologySourceFile.admit(
            workspace,
            sourceRoot,
            WorkspaceSourcePath.parse("alpha/src/main/kotlin/Alpha.kt").refined(),
            WorkspaceSourceContentHash.parse("a".repeat(64)).refined(),
        ).refined()
        val candidates = TopologyCandidateSet.admit(workspace, listOf(file)).refined()
        val complete = CompleteTopologyFile.admit(file, emptyList(), emptyList()).refined()
        val generation = CompleteTopologyGeneration.admit(
            workspace,
            candidates.files,
            listOf(complete),
        ).refined()
        return Fixture(
            workspace,
            TopologyCandidateEnumeration.Complete(candidates),
            complete,
            generation,
        )
    }

    private fun workspace(
        sourceRoot: SourceRoot,
        state: String = "workspace-state",
        generation: Long = 7,
    ): PublishedWorkspace {
        val candidate = WorkspaceCandidate(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
            WorkspaceStateIdentity.parse(state).refined(),
        )
        val reconciled = ReconciledWorkspace.admit(
            candidate,
            WorkspaceEvidenceKind.entries.toSet(),
            listOf(sourceRoot),
        ).refined()
        return PublishedWorkspace.publish(reconciled, EvidenceGeneration.parse(generation).refined())
    }

    private fun sourceRoot(): SourceRoot = SourceRoot.admit(
        GradleSourceRootEvidence(
            "alpha.main",
            ".",
            ":alpha",
            "main",
            "alpha/src/main/kotlin",
            SourceRootProvenance.Authored,
        ),
    ).refined()

    private fun ready(workspace: PublishedWorkspace): WorkspaceInspectionOperations =
        WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(workspace) }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}

private data class Fixture(
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

private data class TestSnapshot(
    override val identity: TopologyWorkspaceIdentity,
    override val manifest: TopologySnapshotManifest,
) : PublishedTopologySnapshot

private class FixedSnapshots(
    private val eligibility: TopologySnapshotEligibility,
    private val publicationCalls: AtomicInteger,
    private val snapshot: PublishedTopologySnapshot,
) : TopologySnapshotStore {
    override fun eligible(identity: TopologyWorkspaceIdentity): TopologySnapshotEligibility =
        eligibility

    override fun read(
        snapshot: PublishedTopologySnapshot,
    ): io.github.amichne.kast.topology.contract.TopologySnapshotContentRead =
        io.github.amichne.kast.topology.contract.TopologySnapshotContentRead.Rejected(
            io.github.amichne.kast.topology.contract.TopologySnapshotReadFailure.STORAGE_UNAVAILABLE,
        )

    override fun publish(
        generation: CompleteTopologyGeneration,
    ): TopologyPublicationResult {
        publicationCalls.incrementAndGet()
        return TopologyPublicationResult.Published(snapshot)
    }
}

private class CurrentGuard(
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

private data object MovedGuard : SemanticReadLeaseGuard {
    override fun <Value> whileCurrent(
        expected: SemanticReadLease,
        operation: () -> Value,
    ): SemanticReadLeaseUse<Value> = SemanticReadLeaseUse.Moved
}
