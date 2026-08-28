package io.github.amichne.kast.runtime.ide.host

import io.github.amichne.kast.evidence.contract.HostedWorkspaceStateLocation
import io.github.amichne.kast.evidence.contract.KastUserStateRoot
import io.github.amichne.kast.evidence.sqlite.SqliteTopologySnapshotStore
import io.github.amichne.kast.evidence.sqlite.SqliteTopologySnapshotStoreOpening
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.CompleteTopologyFile
import io.github.amichne.kast.topology.contract.TopologyBuildResult
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumeration
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumerator
import io.github.amichne.kast.topology.contract.TopologyCandidateSet
import io.github.amichne.kast.topology.contract.TopologyFileExtraction
import io.github.amichne.kast.topology.contract.TopologyFileExtractor
import io.github.amichne.kast.topology.contract.TopologySourceFile
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.GradleSourceRootEvidence
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HostedTopologyLifecycleTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `topology build survives host restart and is reused without extraction`() = runTest {
        val fixture = fixture()
        val state = HostedWorkspaceStateLocation.locate(
            KastUserStateRoot.parse(temporary.toString()).refined(),
            fixture.workspace.root,
        ).refined()
        val extractionCalls = AtomicInteger()
        val candidates = TopologyCandidateEnumerator { fixture.enumeration }
        val extractor = TopologyFileExtractor {
            extractionCalls.incrementAndGet()
            TopologyFileExtraction.Complete(fixture.complete)
        }

        val first = HostedTopologyComposition.create(
            HostedWorkspaceOperations(fixture.workspace),
            HostedTopologyRuntimePorts(candidates, extractor, open(state)),
        ).build.build()
        assertInstanceOf(TopologyBuildResult.Published::class.java, first)
        assertEquals(1, extractionCalls.get())

        val reopened = HostedTopologyComposition.create(
            HostedWorkspaceOperations(fixture.workspace),
            HostedTopologyRuntimePorts(candidates, extractor, open(state)),
        ).build.build()
        assertInstanceOf(TopologyBuildResult.Reused::class.java, reopened)
        assertEquals(1, extractionCalls.get())
    }

    private fun open(state: HostedWorkspaceStateLocation): SqliteTopologySnapshotStore = when (
        val opened = SqliteTopologySnapshotStore.open(state.topologyDatabase)
    ) {
        is SqliteTopologySnapshotStoreOpening.Opened -> opened.store
        is SqliteTopologySnapshotStoreOpening.Rejected -> error(opened.failure.toString())
    }

    private fun fixture(): Fixture {
        val sourceRoot = SourceRoot.admit(
            GradleSourceRootEvidence(
                ideaModuleName = "app",
                workspaceRelativeBuildRoot = ".",
                gradleProjectPath = ":app",
                sourceSetName = "main",
                workspaceRelativeSourceRoot = "app/src/main/kotlin",
                provenance = SourceRootProvenance.Authored,
            ),
        ).refined()
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(
            temporary.resolve("workspace"),
        ).refined()
        val reconciled = ReconciledWorkspace.admit(
            WorkspaceCandidate(root, WorkspaceStateIdentity.parse("state-v1").refined()),
            WorkspaceEvidenceKind.entries.toSet(),
            listOf(sourceRoot),
        ).refined()
        val workspace = PublishedWorkspace.publish(
            reconciled,
            EvidenceGeneration.parse(1).refined(),
        )
        val file = TopologySourceFile.admit(
            workspace,
            sourceRoot,
            WorkspaceSourcePath.parse("app/src/main/kotlin/App.kt").refined(),
            WorkspaceSourceContentHash.parse("a".repeat(64)).refined(),
        ).refined()
        val candidates = TopologyCandidateSet.admit(workspace, listOf(file)).refined()
        val complete = CompleteTopologyFile.admit(file, emptyList(), emptyList()).refined()
        return Fixture(
            workspace,
            TopologyCandidateEnumeration.Complete(candidates),
            complete,
        )
    }

    private data class Fixture(
        val workspace: PublishedWorkspace,
        val enumeration: TopologyCandidateEnumeration.Complete,
        val complete: CompleteTopologyFile,
    )

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
