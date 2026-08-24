package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.runtime.composition.protocol.graph.toProtocolCoverage
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.topology.contract.CompleteTopologyFile
import io.github.amichne.kast.topology.contract.CompleteTopologyGeneration
import io.github.amichne.kast.topology.contract.TopologyEdge
import io.github.amichne.kast.topology.contract.TopologyEdgeKind
import io.github.amichne.kast.topology.contract.TopologySourceFile
import io.github.amichne.kast.topology.contract.TopologySymbol
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

class TopologyCoverageProjectionTest {
    @Test
    fun `coverage projection retains structured candidate and endpoint file evidence`() {
        val sourceRoot = sourceRoot()
        val workspace = workspace(sourceRoot)
        val candidate = sourceFile(workspace, sourceRoot, 'a')
        val completed = sourceFile(workspace, sourceRoot, 'b')
        val indexed = symbol(completed, "alpha")
        val contradictory = symbol(completed, "bravo")
        val edge = TopologyEdge.fromBoundary(
            TopologyEdgeKind.REFERENCE,
            contradictory,
            indexed,
            0,
            1,
        ).refined()
        val complete = CompleteTopologyFile.admit(
            completed,
            listOf(indexed),
            listOf(edge),
        ).refined()
        val internalFailure = when (
            val generation = CompleteTopologyGeneration.admit(
                workspace,
                listOf(candidate),
                listOf(complete),
            )
        ) {
            is Refinement.Refined -> error("contradictory coverage unexpectedly admitted")
            is Refinement.Rejected -> generation.failure
        }

        val publicFailure = internalFailure.toProtocolCoverage().refined()
        val mismatch = publicFailure.candidateEvidenceMismatches.single()
        val endpoint = publicFailure.mismatchedEdgeEndpoints.single()

        assertEquals("a".repeat(64), mismatch.candidate.contentHash.value)
        assertEquals("b".repeat(64), mismatch.completed.contentHash.value)
        assertEquals("/workspace", mismatch.candidate.workspace.root.value)
        assertEquals(7, mismatch.candidate.workspace.generation.value)
        assertEquals("workspace-state", mismatch.candidate.workspace.sourceState.value)
        assertEquals("alpha.main", mismatch.candidate.sourceRoot.module.value)
        assertEquals(":alpha", mismatch.candidate.sourceRoot.projectPath.value)
        assertEquals("src/main/kotlin", mismatch.candidate.sourceRoot.location.value)
        assertEquals("b".repeat(64), endpoint.fileEvidence.contentHash.value)
        assertEquals("src/main/kotlin/Alpha.kt", endpoint.fileEvidence.path.value)
    }

    private fun sourceFile(
        workspace: PublishedWorkspace,
        sourceRoot: SourceRoot,
        hash: Char,
    ): TopologySourceFile = TopologySourceFile.admit(
        workspace,
        sourceRoot,
        WorkspaceSourcePath.parse("src/main/kotlin/Alpha.kt").refined(),
        WorkspaceSourceContentHash.parse(hash.toString().repeat(64)).refined(),
    ).refined()

    private fun symbol(file: TopologySourceFile, name: String): TopologySymbol {
        val absolute = Path.of(file.workspace.lease.workspaceRoot.value).resolve(file.path.value)
        val fileIdentity = SymbolDiscoveryFileIdentity.fromBoundary(
            file.workspace.lease.workspaceRoot,
            absolute,
            absolute.toUri().toString(),
        ).refined()
        val evidence = CompilerGroundedSymbolEvidence.fromBoundary(
            fileIdentity,
            0,
            name.length,
            name,
            "sample.$name",
            CompilerSymbolKind.FUNCTION,
            CompilerSymbolIdentity.parse("function|sample.shared|-|||0").refined(),
        ).refined()
        return TopologySymbol.admit(file, evidence).refined()
    }

    private fun workspace(sourceRoot: SourceRoot): PublishedWorkspace {
        val candidate = WorkspaceCandidate(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
            WorkspaceStateIdentity.parse("workspace-state").refined(),
        )
        val reconciled = ReconciledWorkspace.admit(
            candidate,
            WorkspaceEvidenceKind.entries.toSet(),
            listOf(sourceRoot),
        ).refined()
        return PublishedWorkspace.publish(reconciled, EvidenceGeneration.parse(7).refined())
    }

    private fun sourceRoot(): SourceRoot = SourceRoot.admit(
        GradleSourceRootEvidence(
            ideaModuleName = "alpha.main",
            workspaceRelativeBuildRoot = ".",
            gradleProjectPath = ":alpha",
            sourceSetName = "main",
            workspaceRelativeSourceRoot = "src/main/kotlin",
            provenance = SourceRootProvenance.Authored,
        ),
    ).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
