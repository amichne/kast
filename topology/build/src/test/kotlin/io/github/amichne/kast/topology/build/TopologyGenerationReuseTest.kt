package io.github.amichne.kast.topology.build

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.topology.contract.CompleteTopologyFile
import io.github.amichne.kast.topology.contract.CompleteTopologyGeneration
import io.github.amichne.kast.topology.contract.PublishedTopologySnapshot
import io.github.amichne.kast.topology.contract.TopologyCandidateSet
import io.github.amichne.kast.topology.contract.TopologyEdge
import io.github.amichne.kast.topology.contract.TopologyEdgeKind
import io.github.amichne.kast.topology.contract.TopologySnapshotContent
import io.github.amichne.kast.topology.contract.TopologySnapshotManifest
import io.github.amichne.kast.topology.contract.TopologySourceFile
import io.github.amichne.kast.topology.contract.TopologySymbol
import io.github.amichne.kast.topology.contract.TopologyWorkspaceIdentity
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class TopologyGenerationReuseTest {
    @Test
    fun `semantic state change cannot reuse stale compiler facts`() {
        val root = sourceRoot()
        val priorWorkspace = workspace(root)
        val priorFile = sourceFile(priorWorkspace, root, "Alpha.kt", 'a')
        val priorComplete = CompleteTopologyFile.admit(
            priorFile,
            emptyList(),
            emptyList(),
        ).refined()
        val priorGeneration = CompleteTopologyGeneration.admit(
            priorWorkspace,
            listOf(priorFile),
            listOf(priorComplete),
        ).refined()
        val content = TopologySnapshotContent.admit(
            Snapshot(
                priorGeneration.identity,
                TopologySnapshotManifest.from(priorGeneration),
            ),
            listOf(priorComplete),
        ).refined()
        val currentWorkspace = workspace(root, "dependency-changed-state", 8)
        val currentFile = sourceFile(currentWorkspace, root, "Alpha.kt", 'a')
        val candidates = TopologyCandidateSet.admit(
            currentWorkspace,
            listOf(currentFile),
        ).refined()

        assertEquals(
            TopologyGenerationReuse.SourceChanged,
            rebindUnchangedTopologyGeneration(currentWorkspace, candidates, content),
        )
    }

    @Test
    fun `stale rebound preserves exact endpoints that share compiler identity`() {
        val root = sourceRoot()
        val priorWorkspace = workspace(root)
        val firstFile = sourceFile(priorWorkspace, root, "First.kt", 'a')
        val secondFile = sourceFile(priorWorkspace, root, "Second.kt", 'b')
        val firstSymbol = symbol(firstFile, "first", 0)
        val secondSymbol = symbol(secondFile, "second", 20)
        val edge = TopologyEdge.fromBoundary(
            TopologyEdgeKind.CALL,
            firstSymbol,
            secondSymbol,
            1,
            2,
        ).refined()
        val firstComplete = CompleteTopologyFile.admit(
            firstFile,
            listOf(firstSymbol),
            listOf(edge),
        ).refined()
        val secondComplete = CompleteTopologyFile.admit(
            secondFile,
            listOf(secondSymbol),
            emptyList(),
        ).refined()
        val priorGeneration = CompleteTopologyGeneration.admit(
            priorWorkspace,
            listOf(firstFile, secondFile),
            listOf(firstComplete, secondComplete),
        ).refined()
        val priorContent = TopologySnapshotContent.admit(
            Snapshot(
                priorGeneration.identity,
                TopologySnapshotManifest.from(priorGeneration),
            ),
            listOf(firstComplete, secondComplete),
        ).refined()
        val currentWorkspace = workspace(root, "workspace-state", 8)
        val currentFirst = sourceFile(currentWorkspace, root, "First.kt", 'a')
        val currentSecond = sourceFile(currentWorkspace, root, "Second.kt", 'b')
        val candidates = TopologyCandidateSet.admit(
            currentWorkspace,
            listOf(currentFirst, currentSecond),
        ).refined()

        val rebound = rebindUnchangedTopologyGeneration(
            currentWorkspace,
            candidates,
            priorContent,
        ) as TopologyGenerationReuse.Rebound

        assertEquals(2, rebound.generation.symbols.size)
        assertEquals(currentFirst, rebound.generation.edges.single().source.file)
        assertEquals(currentSecond, rebound.generation.edges.single().target.file)
    }

    private fun sourceFile(
        workspace: PublishedWorkspace,
        root: SourceRoot,
        name: String,
        hashDigit: Char,
    ): TopologySourceFile = TopologySourceFile.admit(
        workspace,
        root,
        WorkspaceSourcePath.parse("alpha/src/main/kotlin/$name").refined(),
        WorkspaceSourceContentHash.parse(hashDigit.toString().repeat(64)).refined(),
    ).refined()

    private fun symbol(
        file: TopologySourceFile,
        name: String,
        start: Int,
    ): TopologySymbol {
        val absolute = Path.of(file.workspace.lease.workspaceRoot.value).resolve(file.path.value)
        val fileIdentity = SymbolDiscoveryFileIdentity.fromBoundary(
            file.workspace.lease.workspaceRoot,
            absolute,
            absolute.toUri().toString(),
        ).refined()
        val evidence = CompilerGroundedSymbolEvidence.fromBoundary(
            fileIdentity,
            start,
            start + name.length,
            name,
            "sample.$name",
            CompilerSymbolKind.FUNCTION,
            CompilerSymbolIdentity.parse("function|sample.shared|-|||0").refined(),
        ).refined()
        return TopologySymbol.admit(file, evidence).refined()
    }

    private fun workspace(
        root: SourceRoot,
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
            listOf(root),
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

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}

private data class Snapshot(
    override val identity: TopologyWorkspaceIdentity,
    override val manifest: TopologySnapshotManifest,
) : PublishedTopologySnapshot
