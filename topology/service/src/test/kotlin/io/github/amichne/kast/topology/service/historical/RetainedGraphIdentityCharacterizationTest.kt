package io.github.amichne.kast.topology.service.historical

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.topology.contract.CompleteTopologyFile
import io.github.amichne.kast.topology.contract.CompleteTopologyGeneration
import io.github.amichne.kast.topology.contract.PublishedTopologySnapshot
import io.github.amichne.kast.topology.contract.TopologySnapshotContent
import io.github.amichne.kast.topology.contract.TopologySnapshotManifest
import io.github.amichne.kast.topology.contract.TopologySourceFile
import io.github.amichne.kast.topology.contract.TopologySymbol
import io.github.amichne.kast.topology.service.GraphIndex
import io.github.amichne.kast.topology.service.TopologyQuotientLevel
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class RetainedGraphIdentityCharacterizationTest {
    @Test
    fun `legacy quotient loses a location distinction that exact content preserves`() {
        val roots = listOf(root("contracts"), root("isolated"))
        val workspace = workspace(roots)
        val files = roots.map { sourceRoot ->
            TopologySourceFile.admit(
                    workspace,
                    sourceRoot,
                    WorkspaceSourcePath.parse("${sourceRoot.location.value}/Api.kt").refined(),
                    WorkspaceSourceContentHash.parse("a".repeat(64)).refined(),
                )
                .refined()
        }
        val symbols = files.map(::symbol)
        assertEquals(symbols[0].evidence.compilerIdentity, symbols[1].evidence.compilerIdentity)
        assertNotEquals(symbols[0].nodeIdentity, symbols[1].nodeIdentity)
        val completed =
            files.zip(symbols).map { (file, declaration) ->
                CompleteTopologyFile.admit(file, listOf(declaration), emptyList()).refined()
            }
        val generation = CompleteTopologyGeneration.admit(workspace, files, completed).refined()
        val snapshot =
            object : PublishedTopologySnapshot {
                override val identity = generation.identity
                override val manifest = TopologySnapshotManifest.from(generation)
            }
        val content = TopologySnapshotContent.admit(snapshot, completed).refined()

        assertEquals(listOf("contracts/src/Api.kt", "isolated/src/Api.kt"), content.symbols.map { it.file.path.value })
        assertEquals(2, content.symbols.size)
        // This is the retained algorithm's limit, not the historical report's contract.
        assertEquals(1, GraphIndex(content).quotient(TopologyQuotientLevel.PROJECT).nodes.size)
    }

    private fun symbol(file: TopologySourceFile): TopologySymbol {
        val absolute = Path.of("/synthetic-graph-oracle").resolve(file.path.value)
        val identity =
            SymbolDiscoveryFileIdentity.fromBoundary(
                    file.workspace.lease.workspaceRoot,
                    absolute,
                    absolute.toUri().toString(),
                )
                .refined()
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    identity,
                    0,
                    10,
                    "Api",
                    "example.Api",
                    CompilerSymbolKind.CLASSLIKE,
                    CanonicalCompilerSignature.classLike("example.Api").refined(),
                )
                .refined()
        return TopologySymbol.admit(file, evidence).refined()
    }

    private fun workspace(roots: List<SourceRoot>): PublishedWorkspace {
        val candidate =
            WorkspaceCandidate(
                CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/synthetic-graph-oracle")).refined(),
                WorkspaceStateIdentity.parse("explicit-graph-characterization").refined(),
            )
        val model = ReconciledWorkspace.admit(candidate, WorkspaceEvidenceKind.entries.toSet(), roots).refined()
        return PublishedWorkspace.publish(model, EvidenceGeneration.parse(1).refined())
    }

    private fun root(module: String): SourceRoot =
        SourceRoot.admit(
                GradleSourceRootEvidence(module, ".", ":$module", "main", "$module/src", SourceRootProvenance.Authored)
            )
            .refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
