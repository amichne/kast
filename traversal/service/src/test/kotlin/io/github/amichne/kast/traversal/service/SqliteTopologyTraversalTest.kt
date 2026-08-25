package io.github.amichne.kast.traversal.service

import io.github.amichne.kast.evidence.sqlite.SqliteTopologyRelationCompiler
import io.github.amichne.kast.evidence.sqlite.SqliteTopologyRelationCompilerOpening
import io.github.amichne.kast.evidence.sqlite.SqliteTopologySnapshotStore
import io.github.amichne.kast.evidence.sqlite.SqliteTopologySnapshotStoreOpening
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.service.RelationService
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.topology.contract.CompleteTopologyFile
import io.github.amichne.kast.topology.contract.CompleteTopologyGeneration
import io.github.amichne.kast.topology.contract.PublishedTopologySnapshot
import io.github.amichne.kast.topology.contract.TopologyEdge
import io.github.amichne.kast.topology.contract.TopologyEdgeKind
import io.github.amichne.kast.topology.contract.TopologyPublicationResult
import io.github.amichne.kast.topology.contract.TopologySnapshotContentRead
import io.github.amichne.kast.topology.contract.TopologySnapshotContentReader
import io.github.amichne.kast.topology.contract.TopologySourceFile
import io.github.amichne.kast.topology.contract.TopologySymbol
import io.github.amichne.kast.traversal.contract.TraversalResult
import io.github.amichne.kast.workspace.contract.GradleSourceRootEvidence
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class SqliteTopologyTraversalTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `public multi hop traversal reads restarted SQLite topology without K2`() {
        val traversalFixture = TraversalTestFixture()
        val a = traversalFixture.selector("a", 10)
        val b = traversalFixture.selector("b", 20)
        val c = traversalFixture.selector("c", 30)
        val workspace = workspace(traversalFixture, sourceRoot())
        val generation = generation(workspace, listOf(a, b, c))
        val database = tempDir.resolve("topology.sqlite")
        val first = store(database)
        val snapshot = assertInstanceOf(
            TopologyPublicationResult.Published::class.java,
            first.publish(generation),
        ).snapshot

        val reopened = store(database)
        val contentReads = CountingTopologySnapshotContentReader(reopened)
        val current = WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(workspace) }
        val relations = RelationService(
            current,
            topologyRelationCompiler(snapshot, contentReads),
        )
        val result = assertInstanceOf(
            TraversalResult.Complete::class.java,
            runSuspend { traversalOperations(relations).run(traversalFixture.plan(a)) },
        )

        assertEquals(2, result.coverage.exactRecordCount.value)
        assertEquals(
            listOf("b", "c"),
            result.page.records.map { it.related.name.value },
        )
        assertEquals(1, contentReads.count)
    }

    @Test
    fun `exact selector location distinguishes duplicate compiler identities`() {
        val traversalFixture = TraversalTestFixture()
        val sharedIdentity = "function|sample.shared|-|||0"
        val a = traversalFixture.selector("a", 10, sharedIdentity)
        val b = traversalFixture.selector("b", 20, sharedIdentity)
        val c = traversalFixture.selector("c", 30)
        val workspace = workspace(traversalFixture, sourceRoot())
        val generation = generation(workspace, listOf(a, b, c))
        val database = tempDir.resolve("duplicate-identity-topology.sqlite")
        val snapshot = assertInstanceOf(
            TopologyPublicationResult.Published::class.java,
            store(database).publish(generation),
        ).snapshot

        val current = WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(workspace) }
        val reopened = store(database)
        val relations = RelationService(current, topologyRelationCompiler(snapshot, reopened))
        val result = assertInstanceOf(
            TraversalResult.Complete::class.java,
            runSuspend { traversalOperations(relations).run(traversalFixture.plan(a)) },
        )

        assertEquals(listOf("b", "c"), result.page.records.map { it.related.name.value })
    }

    private fun generation(
        workspace: PublishedWorkspace,
        selectors: List<SymbolSelector>,
    ): CompleteTopologyGeneration {
        val root = workspace.sourceRoots.single()
        val files = selectors.associateWith { selector ->
            val path = (selector.file.stableValue.removePrefix("/workspace/"))
            TopologySourceFile.admit(
                workspace,
                root,
                WorkspaceSourcePath.parse(path).refined(),
                WorkspaceSourceContentHash.parse(
                    selector.name.value.first().toString().repeat(64),
                ).refined(),
            ).refined()
        }
        val symbols = files.mapValues { (selector, file) -> topologySymbol(file, selector) }
        val edges = listOf(
            topologyEdge(symbols, selectors[0], selectors[1]),
            topologyEdge(symbols, selectors[1], selectors[2]),
        )
        val complete = files.map { (selector, file) ->
            CompleteTopologyFile.admit(
                file,
                listOf(symbols.getValue(selector)),
                edges.filter { it.source == symbols.getValue(selector) },
            ).refined()
        }
        return CompleteTopologyGeneration.admit(
            workspace,
            files.values.toList(),
            complete,
        ).refined()
    }

    private fun topologySymbol(
        file: TopologySourceFile,
        selector: SymbolSelector,
    ): TopologySymbol {
        val qualified = when (val identity = selector.qualifiedIdentity) {
            is ExactDeclarationQualifiedIdentity.Available -> identity.value
            ExactDeclarationQualifiedIdentity.Unavailable -> null
        }
        val evidence = CompilerGroundedSymbolEvidence.fromBoundary(
            selector.file,
            selector.range.startInclusive,
            selector.range.endExclusive,
            selector.name.value,
            qualified,
            selector.kind,
            SymbolDescription.from(selector).compilerIdentity,
        ).refined()
        return TopologySymbol.admit(file, evidence).refined()
    }

    private fun topologyEdge(
        symbols: Map<SymbolSelector, TopologySymbol>,
        source: SymbolSelector,
        target: SymbolSelector,
    ): TopologyEdge = TopologyEdge.fromBoundary(
        TopologyEdgeKind.CALL,
        symbols.getValue(source),
        symbols.getValue(target),
        source.range.startInclusive,
        source.range.startInclusive + 1,
    ).refined()

    private fun workspace(fixture: TraversalTestFixture, root: SourceRoot): PublishedWorkspace {
        val candidate = WorkspaceCandidate(
            fixture.lease.workspaceRoot,
            WorkspaceStateIdentity.parse("sqlite-traversal-state").refined(),
        )
        return PublishedWorkspace.publish(
            ReconciledWorkspace.admit(
                candidate,
                WorkspaceEvidenceKind.entries.toSet(),
                listOf(root),
            ).refined(),
            fixture.lease.generation,
        )
    }

    private fun sourceRoot(): SourceRoot = SourceRoot.admit(
        GradleSourceRootEvidence(
            "root.main",
            ".",
            ":",
            "main",
            "src",
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

    private fun topologyRelationCompiler(
        snapshot: PublishedTopologySnapshot,
        reader: TopologySnapshotContentReader,
    ): SqliteTopologyRelationCompiler = when (
        val opened = SqliteTopologyRelationCompiler.open(snapshot, reader)
    ) {
        is SqliteTopologyRelationCompilerOpening.Opened -> opened.compiler
        is SqliteTopologyRelationCompilerOpening.Rejected -> error(opened.failure)
    }

    private fun <Value> runSuspend(block: suspend () -> Value): Value {
        var outcome: Result<Value>? = null
        block.startCoroutine(
            object : Continuation<Value> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Value>) {
                    outcome = result
                }
            },
        )
        return checkNotNull(outcome).getOrThrow()
    }
}

private class CountingTopologySnapshotContentReader(
    private val delegate: TopologySnapshotContentReader,
) : TopologySnapshotContentReader {
    var count: Int = 0
        private set

    override fun read(snapshot: PublishedTopologySnapshot): TopologySnapshotContentRead {
        count += 1
        return delegate.read(snapshot)
    }
}
