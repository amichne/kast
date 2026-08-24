package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.topology.contract.CompleteTopologyFile
import io.github.amichne.kast.topology.contract.CompleteTopologyGeneration
import io.github.amichne.kast.topology.contract.TopologyEdge
import io.github.amichne.kast.topology.contract.TopologyEdgeKind
import io.github.amichne.kast.topology.contract.TopologyPublicationResult
import io.github.amichne.kast.topology.contract.TopologySnapshotContentRead
import io.github.amichne.kast.topology.contract.TopologySnapshotEligibility
import io.github.amichne.kast.topology.contract.TopologySnapshotReadFailure
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
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class SqliteTopologySnapshotStoreTest {
    @TempDir
    lateinit var tempDir: Path
    @Test
    fun `exact symbols and edges survive store restart`() {
        val generation = generation(workspace("state-a", 7), "a", "b")
        val path = databasePath("restart")
        val first = store(path)
        val published = assertInstanceOf(
            TopologyPublicationResult.Published::class.java,
            first.publish(generation),
        ).snapshot

        val reopened = store(path)
        val eligible = assertInstanceOf(
            TopologySnapshotEligibility.Eligible::class.java,
            reopened.eligible(generation.identity),
        ).snapshot
        val content = assertInstanceOf(
            TopologySnapshotContentRead.Loaded::class.java,
            reopened.read(eligible),
        ).content

        assertEquals(published.identity, eligible.identity)
        assertEquals(published.manifest, eligible.manifest)
        assertEquals(
            generation.symbols.map(TopologySymbol::canonicalProjection),
            content.symbols.map(TopologySymbol::canonicalProjection),
        )
        assertEquals(
            generation.edges.map(TopologyEdge::canonicalProjection),
            content.edges.map(TopologyEdge::canonicalProjection),
        )
    }

    @Test
    fun `location-distinct symbols with one compiler identity survive store restart`() {
        val workspace = workspace("state-a", 7)
        val root = workspace.sourceRoots.single()
        val sourceFile = sourceFile(workspace, root, "src/main/kotlin/Source.kt", "a")
        val targetFile = sourceFile(workspace, root, "src/main/kotlin/Target.kt", "b")
        val source = symbol(sourceFile, "shared", "sample.Shared", 0, 12)
        val target = symbol(targetFile, "shared", "sample.Shared", 20, 32)
        val edge = TopologyEdge.fromBoundary(
            TopologyEdgeKind.CALL,
            source,
            target,
            6,
            12,
        ).refined()
        val generation = CompleteTopologyGeneration.admit(
            workspace,
            listOf(sourceFile, targetFile),
            listOf(
                CompleteTopologyFile.admit(sourceFile, listOf(source), listOf(edge)).refined(),
                CompleteTopologyFile.admit(targetFile, listOf(target), emptyList()).refined(),
            ),
        ).refined()
        val path = databasePath("duplicate-identity")
        val published = assertInstanceOf(
            TopologyPublicationResult.Published::class.java,
            store(path).publish(generation),
        ).snapshot

        val content = assertInstanceOf(
            TopologySnapshotContentRead.Loaded::class.java,
            store(path).read(published),
        ).content

        assertEquals(2, content.symbols.size)
        assertEquals(
            listOf("src/main/kotlin/Source.kt", "src/main/kotlin/Target.kt"),
            content.symbols.map { it.file.path.value },
        )
        assertEquals("src/main/kotlin/Source.kt", content.edges.single().source.file.path.value)
        assertEquals("src/main/kotlin/Target.kt", content.edges.single().target.file.path.value)
    }

    @Test
    fun `different workspace identity makes prior snapshot stale without deleting it`() {
        val first = generation(workspace("state-a", 7), "a", "b")
        val store = store(databasePath("stale"))
        val published = assertInstanceOf(
            TopologyPublicationResult.Published::class.java,
            store.publish(first),
        ).snapshot
        val moved = TopologyWorkspaceIdentity.from(workspace("state-b", 8))

        assertEquals(
            published,
            assertInstanceOf(
                TopologySnapshotEligibility.Stale::class.java,
                store.eligible(moved),
            ).latest,
        )
        assertInstanceOf(
            TopologySnapshotEligibility.Eligible::class.java,
            store.eligible(first.identity),
        )
    }

    @Test
    fun `failed rebuild preserves the prior published snapshot`() {
        val first = generation(workspace("state-a", 7), "a", "b")
        val second = generation(workspace("state-b", 8), "c", "d")
        val path = databasePath("rollback")
        val initial = store(path)
        val prior = assertInstanceOf(
            TopologyPublicationResult.Published::class.java,
            initial.publish(first),
        ).snapshot
        val failing = faultInjectingStore(path) { point ->
            if (point == SqliteTopologyFaultPoint.BEFORE_COMMIT) error("injected failure")
        }

        assertInstanceOf(
            TopologyPublicationResult.Rejected::class.java,
            failing.publish(second),
        )
        val reopened = store(path)
        assertEquals(
            prior.manifest,
            assertInstanceOf(
                TopologySnapshotEligibility.Eligible::class.java,
                reopened.eligible(first.identity),
            ).snapshot.manifest,
        )
        assertEquals(
            prior,
            assertInstanceOf(
                TopologySnapshotEligibility.Stale::class.java,
                reopened.eligible(second.identity),
            ).latest,
        )
    }

    @Test
    fun `corrupted exact content is never eligible for reuse`() {
        val generation = generation(workspace("state-a", 7), "a", "b")
        val path = databasePath("corrupt-eligibility")
        val store = store(path)
        assertInstanceOf(
            TopologyPublicationResult.Published::class.java,
            store.publish(generation),
        )
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("UPDATE topology_symbol_v2 SET symbol_name = 'tampered'")
            }
        }

        val rejected = assertInstanceOf(
            TopologySnapshotEligibility.Rejected::class.java,
            store.eligible(generation.identity),
        )

        assertEquals(TopologySnapshotReadFailure.CORRUPT_SNAPSHOT, rejected.failure)
    }

    @Test
    fun `row reconstruction returns typed corruption instead of throwing`() {
        val generation = generation(workspace("state-a", 7), "a", "b")
        val path = databasePath("typed-corruption")
        val published = assertInstanceOf(
            TopologyPublicationResult.Published::class.java,
            store(path).publish(generation),
        ).snapshot
        mutate(path, "UPDATE topology_symbol_v2 SET symbol_name = ''")

        val reconstruction = DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.readTopologyContent(published)
        }

        assertEquals(
            TopologySnapshotContentRead.Rejected(TopologySnapshotReadFailure.CORRUPT_SNAPSHOT),
            reconstruction,
        )
    }

    @Test
    fun `corrupted stale content is rejected before stale evidence is returned`() {
        val generation = generation(workspace("state-a", 7), "a", "b")
        val path = databasePath("corrupt-stale")
        val store = store(path)
        assertInstanceOf(TopologyPublicationResult.Published::class.java, store.publish(generation))
        mutate(path, "DELETE FROM topology_edge_v2")

        assertCorrupt(store.eligible(TopologyWorkspaceIdentity.from(workspace("state-b", 8))))
    }

    @Test
    fun `missing persisted edge is rejected as incomplete content`() {
        val generation = generation(workspace("state-a", 7), "a", "b")
        val path = databasePath("missing-edge")
        val store = store(path)
        assertInstanceOf(TopologyPublicationResult.Published::class.java, store.publish(generation))
        mutate(path, "DELETE FROM topology_edge_v2")

        assertCorrupt(store.eligible(generation.identity))
    }

    @Test
    fun `dangling persisted edge target is rejected`() {
        val generation = generation(workspace("state-a", 7), "a", "b")
        val path = databasePath("dangling-edge")
        val store = store(path)
        assertInstanceOf(TopologyPublicationResult.Published::class.java, store.publish(generation))
        mutate(path, "UPDATE topology_edge_v2 SET target_symbol_id = 9223372036854775807")

        assertCorrupt(store.eligible(generation.identity))
    }

    @Test
    fun `persisted edge occurrence cannot move to another existing file`() {
        val generation = generation(workspace("state-a", 7), "a", "b")
        val path = databasePath("moved-occurrence")
        val store = store(path)
        assertInstanceOf(TopologyPublicationResult.Published::class.java, store.publish(generation))
        mutate(
            path,
            "UPDATE topology_edge_v2 SET occurrence_file_path = 'src/main/kotlin/Target.kt'",
        )

        assertCorrupt(store.eligible(generation.identity))
    }

    private fun generation(
        workspace: PublishedWorkspace,
        sourceHash: String,
        targetHash: String,
    ): CompleteTopologyGeneration {
        val root = workspace.sourceRoots.single()
        val sourceFile = sourceFile(workspace, root, "src/main/kotlin/Source.kt", sourceHash)
        val targetFile = sourceFile(workspace, root, "src/main/kotlin/Target.kt", targetHash)
        val source = symbol(sourceFile, "Source", "sample.Source", 0, 12)
        val target = symbol(targetFile, "Target", "sample.Target", 0, 12)
        val edge = TopologyEdge.fromBoundary(
            TopologyEdgeKind.CALL,
            source,
            target,
            6,
            12,
        ).refined()
        val sourceComplete = CompleteTopologyFile.admit(
            sourceFile,
            listOf(source),
            listOf(edge),
        ).refined()
        val targetComplete = CompleteTopologyFile.admit(
            targetFile,
            listOf(target),
            emptyList(),
        ).refined()
        return CompleteTopologyGeneration.admit(
            workspace,
            listOf(sourceFile, targetFile),
            listOf(sourceComplete, targetComplete),
        ).refined()
    }

    private fun symbol(
        file: TopologySourceFile,
        name: String,
        identity: String,
        start: Int,
        end: Int,
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
            end,
            name,
            identity,
            CompilerSymbolKind.CLASSLIKE,
            CompilerSymbolIdentity.parse("class|$identity").refined(),
        ).refined()
        return TopologySymbol.admit(file, evidence).refined()
    }

    private fun sourceFile(
        workspace: PublishedWorkspace,
        sourceRoot: SourceRoot,
        path: String,
        hash: String,
    ): TopologySourceFile = TopologySourceFile.admit(
        workspace,
        sourceRoot,
        WorkspaceSourcePath.parse(path).refined(),
        WorkspaceSourceContentHash.parse(hash.repeat(64)).refined(),
    ).refined()

    private fun workspace(state: String, generation: Long): PublishedWorkspace {
        val root = SourceRoot.admit(
            GradleSourceRootEvidence(
                "root.main",
                ".",
                ":",
                "main",
                "src/main/kotlin",
                SourceRootProvenance.Authored,
            ),
        ).refined()
        val candidate = WorkspaceCandidate(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
            WorkspaceStateIdentity.parse(state).refined(),
        )
        val reconciled = ReconciledWorkspace.admit(
            candidate,
            WorkspaceEvidenceKind.entries.toSet(),
            listOf(root),
        ).refined()
        return PublishedWorkspace.publish(
            reconciled,
            EvidenceGeneration.parse(generation).refined(),
        )
    }

    private fun databasePath(name: String): Path =
        tempDir.resolve(name).resolve("topology.sqlite").also { Files.createDirectories(it.parent) }

    private fun mutate(path: Path, sql: String) {
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { statement -> statement.executeUpdate(sql) }
        }
    }

    private fun assertCorrupt(eligibility: TopologySnapshotEligibility) {
        val rejected = assertInstanceOf(
            TopologySnapshotEligibility.Rejected::class.java,
            eligibility,
        )
        assertEquals(TopologySnapshotReadFailure.CORRUPT_SNAPSHOT, rejected.failure)
    }

    private fun store(path: Path): SqliteTopologySnapshotStore = when (
        val opened = SqliteTopologySnapshotStore.open(path)
    ) {
        is SqliteTopologySnapshotStoreOpening.Opened -> opened.store
        is SqliteTopologySnapshotStoreOpening.Rejected -> error(opened.failure)
    }

    private fun faultInjectingStore(
        path: Path,
        inject: SqliteTopologyFaultInjector,
    ): SqliteTopologySnapshotStore = when (
        val opened = SqliteTopologySnapshotStore.open(path, inject)
    ) {
        is SqliteTopologySnapshotStoreOpening.Opened -> opened.store
        is SqliteTopologySnapshotStoreOpening.Rejected -> error(opened.failure)
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
