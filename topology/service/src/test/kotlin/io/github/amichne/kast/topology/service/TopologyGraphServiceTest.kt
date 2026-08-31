package io.github.amichne.kast.topology.service

import io.github.amichne.kast.evidence.sqlite.SqliteTopologySnapshotStore
import io.github.amichne.kast.evidence.sqlite.SqliteTopologySnapshotStoreOpening
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.topology.contract.CompleteTopologyFile
import io.github.amichne.kast.topology.contract.CompleteTopologyGeneration
import io.github.amichne.kast.topology.contract.TopologyEdge
import io.github.amichne.kast.topology.contract.TopologyEdgeKind
import io.github.amichne.kast.topology.contract.TopologyPublicationResult
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
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TopologyGraphServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `all repository graph operations read one restarted SQLite snapshot deterministically`() {
        val fixture = fixture()
        val database = tempDir.resolve("topology.sqlite")
        val firstStore = store(database)
        val published = assertInstanceOf(
            TopologyPublicationResult.Published::class.java,
            firstStore.publish(fixture.generation),
        ).snapshot

        val reopened = store(database)
        val graph = assertInstanceOf(
            TopologyGraphOpen.Opened::class.java,
            topologyGraphOperations(reopened).open(published),
        ).graph

        val traversal = assertInstanceOf(
            TopologyGraphTraversal.Traversed::class.java,
            graph.traverse(fixture.identities.getValue("A")),
        ).result
        assertEquals(listOf(0, 1, 2, 3, 4, 5), traversal.visits.map { it.depth.value })
        assertEquals(
            listOf("A", "B", "C", "D", "E", "F"),
            traversal.visits.map { it.symbol.evidence.name.value },
        )

        val reachable = assertInstanceOf(
            TopologyReachability.Reachable::class.java,
            graph.reachability(
                fixture.identities.getValue("A"),
                fixture.identities.getValue("F"),
            ),
        )
        assertEquals(5, reachable.path.edges.size)
        assertEquals(2, graph.cycles().size)
        assertEquals(listOf(3, 2, 1), graph.stronglyConnectedComponents().map { it.symbols.size })
        assertEquals(listOf(3, 2, 1), graph.condensation().order.map { it.symbols.size })
        assertEquals(7, graph.quotient(TopologyQuotientLevel.FILE).edges.size)
        assertEquals(2, graph.quotient(TopologyQuotientLevel.PROJECT).edges.size)
        assertEquals(4, graph.quotient(TopologyQuotientLevel.SOURCE_SET).edges.size)

        val firstProjection = graph.canonicalProjection()
        val secondGraph = assertInstanceOf(
            TopologyGraphOpen.Opened::class.java,
            topologyGraphOperations(store(database)).open(published),
        ).graph
        assertEquals(firstProjection, secondGraph.canonicalProjection())
    }

    private fun fixture(): GraphFixture {
        val alpha = sourceRoot("alpha.main", ":alpha", "main", "alpha/src/main/kotlin")
        val betaMain = sourceRoot("beta.main", ":beta", "main", "beta/src/main/kotlin")
        val betaTest = sourceRoot("beta.test", ":beta", "test", "beta/src/test/kotlin")
        val workspace = workspace(listOf(alpha, betaMain, betaTest))
        val roots = mapOf("A" to alpha, "B" to alpha, "C" to betaMain, "D" to betaMain,
            "E" to betaTest, "F" to betaTest)
        val files = roots.mapValues { (name, root) ->
            sourceFile(workspace, root, "${root.location.value}/$name.kt", name.lowercase())
        }
        val symbols = files.mapValues { (name, file) -> symbol(file, name) }
        val edges = listOf(
            edge(symbols, "A", "B", TopologyEdgeKind.CALL),
            edge(symbols, "B", "C", TopologyEdgeKind.CALL),
            edge(symbols, "C", "A", TopologyEdgeKind.REFERENCE),
            edge(symbols, "C", "D", TopologyEdgeKind.TYPE_USE),
            edge(symbols, "D", "E", TopologyEdgeKind.REFERENCE),
            edge(symbols, "E", "D", TopologyEdgeKind.REFERENCE),
            edge(symbols, "E", "F", TopologyEdgeKind.CALL),
        )
        val complete = files.map { (name, file) ->
            CompleteTopologyFile.admit(
                file,
                listOf(symbols.getValue(name)),
                edges.filter { it.source == symbols.getValue(name) }.sorted(),
            ).refined()
        }
        val generation = CompleteTopologyGeneration.admit(
            workspace,
            files.values.toList(),
            complete,
        ).refined()
        return GraphFixture(
            generation,
            symbols.mapValues { it.value.evidence.compilerIdentity },
        )
    }

    private fun edge(
        symbols: Map<String, TopologySymbol>,
        source: String,
        target: String,
        kind: TopologyEdgeKind,
    ): TopologyEdge = TopologyEdge.fromBoundary(
        kind,
        symbols.getValue(source),
        symbols.getValue(target),
        1,
        2,
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
            8,
            name,
            "sample.$name",
            CompilerSymbolKind.CLASSLIKE,
            CanonicalCompilerSignature.classLike("sample.$name").refined(),
        ).refined()
        return TopologySymbol.admit(file, evidence).refined()
    }

    private fun sourceFile(
        workspace: PublishedWorkspace,
        root: SourceRoot,
        path: String,
        hashSeed: String,
    ): TopologySourceFile = TopologySourceFile.admit(
        workspace,
        root,
        WorkspaceSourcePath.parse(path).refined(),
        WorkspaceSourceContentHash.parse(hashSeed.repeat(64)).refined(),
    ).refined()

    private fun workspace(roots: List<SourceRoot>): PublishedWorkspace {
        val candidate = WorkspaceCandidate(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
            WorkspaceStateIdentity.parse("graph-state").refined(),
        )
        val reconciled = ReconciledWorkspace.admit(
            candidate,
            WorkspaceEvidenceKind.entries.toSet(),
            roots,
        ).refined()
        return PublishedWorkspace.publish(reconciled, EvidenceGeneration.parse(41).refined())
    }

    private fun sourceRoot(
        module: String,
        project: String,
        sourceSet: String,
        location: String,
    ): SourceRoot = SourceRoot.admit(
        GradleSourceRootEvidence(
            module,
            ".",
            project,
            sourceSet,
            location,
            SourceRootProvenance.Authored,
        ),
    ).refined()

    private fun store(path: Path): SqliteTopologySnapshotStore {
        Files.createDirectories(path.parent)
        return when (val opened = SqliteTopologySnapshotStore.open(path)) {
            is SqliteTopologySnapshotStoreOpening.Opened -> opened.store
            is SqliteTopologySnapshotStoreOpening.Rejected -> error(opened.failure)
        }
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}

private data class GraphFixture(
    val generation: CompleteTopologyGeneration,
    val identities: Map<String, CompilerSymbolIdentity>,
)
