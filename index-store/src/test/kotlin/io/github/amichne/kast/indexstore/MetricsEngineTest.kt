package io.github.amichne.kast.indexstore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class MetricsEngineTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `ranks symbols by incoming references`() {
        val root = seededWorkspace()

        MetricsEngine(root).use { metrics ->
            assertEquals(
                listOf(
                    FanInMetric(
                        targetFqName = "lib.Foo",
                        targetPath = "/lib/Foo.kt",
                        targetModulePath = ":lib",
                        targetSourceSet = "main",
                        occurrenceCount = 3,
                        sourceFileCount = 2,
                        sourceModuleCount = 1,
                    ),
                    FanInMetric(
                        targetFqName = "app.A",
                        targetPath = "/app/A.kt",
                        targetModulePath = ":app",
                        targetSourceSet = "main",
                        occurrenceCount = 1,
                        sourceFileCount = 1,
                        sourceModuleCount = 1,
                    ),
                ),
                metrics.fanInRanking(limit = 2),
            )
        }
    }

    @Test
    fun `ranks files by outgoing references`() {
        val root = seededWorkspace()

        MetricsEngine(root).use { metrics ->
            assertEquals(
                listOf(
                    FanOutMetric(
                        sourcePath = "/app/A.kt",
                        sourceModulePath = ":app",
                        sourceSourceSet = "main",
                        occurrenceCount = 3,
                        targetSymbolCount = 2,
                        targetFileCount = 2,
                        targetModuleCount = 1,
                        externalTargetCount = 0,
                    ),
                    FanOutMetric(
                        sourcePath = "/app/B.kt",
                        sourceModulePath = ":app",
                        sourceSourceSet = "main",
                        occurrenceCount = 2,
                        targetSymbolCount = 2,
                        targetFileCount = 2,
                        targetModuleCount = 2,
                        externalTargetCount = 0,
                    ),
                ),
                metrics.fanOutRanking(limit = 2),
            )
        }
    }

    @Test
    fun `tracks external fan out targets without changing indexed counts`() {
        val root = seededWorkspace()
        SqliteSourceIndexStore(root).use { store ->
            store.upsertSymbolReference("/app/B.kt", 30, "external.LibrarySymbol", null, null)
        }

        MetricsEngine(root).use { metrics ->
            assertEquals(
                FanOutMetric(
                    sourcePath = "/app/B.kt",
                    sourceModulePath = ":app",
                    sourceSourceSet = "main",
                    occurrenceCount = 3,
                    targetSymbolCount = 3,
                    targetFileCount = 2,
                    targetModuleCount = 2,
                    externalTargetCount = 1,
                ),
                metrics.fanOutRanking(limit = 2).single { it.sourcePath == "/app/B.kt" },
            )
        }
    }

    @Test
    fun `counts cross-module reference pairs`() {
        val root = seededWorkspace()

        MetricsEngine(root).use { metrics ->
            assertEquals(
                listOf(
                    ModuleCouplingMetric(
                        sourceModulePath = ":app",
                        sourceSourceSet = "main",
                        targetModulePath = ":lib",
                        targetSourceSet = "main",
                        referenceCount = 4,
                    ),
                ),
                metrics.moduleCouplingMatrix(),
            )
        }
    }

    @Test
    fun `reports indexed identifiers with no inbound references`() {
        val root = seededWorkspace()

        MetricsEngine(root).use { metrics ->
            val candidates = metrics.deadCodeCandidates()

            assertTrue(
                candidates.any {
                    it.identifier == "Unused" &&
                        it.path == "/app/Unused.kt" &&
                        it.modulePath == ":app" &&
                        it.sourceSet == "main" &&
                        it.packageName == "app" &&
                        it.confidence == MetricsConfidence.LOW
                },
            )
            assertFalse(candidates.any { it.identifier == "Foo" && it.path == "/lib/Foo.kt" })
        }
    }

    @Test
    fun `walks impact radius through files that reference impacted files`() {
        val root = seededWorkspace()

        MetricsEngine(root).use { metrics ->
            assertEquals(
                listOf(
                    ChangeImpactNode(
                        sourcePath = "/app/A.kt",
                        depth = 1,
                        viaTargetFqName = "lib.Foo",
                        occurrenceCount = 2,
                        semantics = ImpactSemantics.FILE_LEVEL_APPROXIMATION,
                    ),
                    ChangeImpactNode(
                        sourcePath = "/app/B.kt",
                        depth = 1,
                        viaTargetFqName = "lib.Foo",
                        occurrenceCount = 1,
                        semantics = ImpactSemantics.FILE_LEVEL_APPROXIMATION,
                    ),
                    ChangeImpactNode(
                        sourcePath = "/app/C.kt",
                        depth = 2,
                        viaTargetFqName = "app.B",
                        occurrenceCount = 1,
                        semantics = ImpactSemantics.FILE_LEVEL_APPROXIMATION,
                    ),
                ),
                metrics.changeImpactRadius(fqName = "lib.Foo", depth = 2),
            )
        }
    }

    @Test
    fun `builds visual graph around focal indexed symbol`() {
        val root = seededWorkspace()

        MetricsEngine(root).use { metrics ->
            assertEquals(
                MetricsGraph(
                    focalNodeId = "symbol:lib.Foo",
                    nodes = listOf(
                        MetricsGraphNode(
                            id = "symbol:lib.Foo",
                            name = "lib.Foo",
                            type = MetricsGraphNodeType.SYMBOL,
                            parentId = "file:/lib/Foo.kt",
                            children = listOf("source-file:/app/A.kt", "source-file:/app/B.kt"),
                            attributes = listOf(
                                "path=/lib/Foo.kt",
                                "module=:lib",
                                "sourceSet=main",
                                "incomingReferences=3",
                                "sourceFiles=2",
                                "sourceModules=1",
                            ),
                        ),
                        MetricsGraphNode(
                            id = "file:/lib/Foo.kt",
                            name = "/lib/Foo.kt",
                            type = MetricsGraphNodeType.FILE,
                            children = listOf("symbol:lib.Foo"),
                            attributes = listOf("role=target", "module=:lib", "sourceSet=main"),
                        ),
                        MetricsGraphNode(
                            id = "source-file:/app/A.kt",
                            name = "/app/A.kt",
                            type = MetricsGraphNodeType.FILE,
                            parentId = "symbol:lib.Foo",
                            children = listOf("via:lib.Foo:/app/A.kt"),
                            attributes = listOf("incomingDepth=1", "references=2", "via=lib.Foo"),
                        ),
                        MetricsGraphNode(
                            id = "via:lib.Foo:/app/A.kt",
                            name = "lib.Foo",
                            type = MetricsGraphNodeType.REFERENCE_EDGE,
                            parentId = "source-file:/app/A.kt",
                            attributes = listOf("from=/app/A.kt", "to=lib.Foo", "references=2"),
                        ),
                        MetricsGraphNode(
                            id = "source-file:/app/B.kt",
                            name = "/app/B.kt",
                            type = MetricsGraphNodeType.FILE,
                            parentId = "symbol:lib.Foo",
                            children = listOf("source-file:/app/C.kt", "via:lib.Foo:/app/B.kt"),
                            attributes = listOf("incomingDepth=1", "references=1", "via=lib.Foo"),
                        ),
                        MetricsGraphNode(
                            id = "via:lib.Foo:/app/B.kt",
                            name = "lib.Foo",
                            type = MetricsGraphNodeType.REFERENCE_EDGE,
                            parentId = "source-file:/app/B.kt",
                            attributes = listOf("from=/app/B.kt", "to=lib.Foo", "references=1"),
                        ),
                        MetricsGraphNode(
                            id = "source-file:/app/C.kt",
                            name = "/app/C.kt",
                            type = MetricsGraphNodeType.FILE,
                            parentId = "source-file:/app/B.kt",
                            children = listOf("via:app.B:/app/C.kt"),
                            attributes = listOf("incomingDepth=2", "references=1", "via=app.B"),
                        ),
                        MetricsGraphNode(
                            id = "via:app.B:/app/C.kt",
                            name = "app.B",
                            type = MetricsGraphNodeType.REFERENCE_EDGE,
                            parentId = "source-file:/app/C.kt",
                            attributes = listOf("from=/app/C.kt", "to=app.B", "references=1"),
                        ),
                    ),
                    edges = listOf(
                        MetricsGraphEdge("file:/lib/Foo.kt", "symbol:lib.Foo", MetricsGraphEdgeType.CONTAINS),
                        MetricsGraphEdge("symbol:lib.Foo", "source-file:/app/A.kt", MetricsGraphEdgeType.REFERENCED_BY, 2),
                        MetricsGraphEdge("source-file:/app/A.kt", "via:lib.Foo:/app/A.kt", MetricsGraphEdgeType.REFERENCES, 2),
                        MetricsGraphEdge("symbol:lib.Foo", "source-file:/app/B.kt", MetricsGraphEdgeType.REFERENCED_BY, 1),
                        MetricsGraphEdge("source-file:/app/B.kt", "via:lib.Foo:/app/B.kt", MetricsGraphEdgeType.REFERENCES, 1),
                        MetricsGraphEdge("source-file:/app/B.kt", "source-file:/app/C.kt", MetricsGraphEdgeType.REFERENCED_BY, 1),
                        MetricsGraphEdge("source-file:/app/C.kt", "via:app.B:/app/C.kt", MetricsGraphEdgeType.REFERENCES, 1),
                    ),
                    index = MetricsGraphIndex(
                        symbolCount = 2,
                        fileCount = 4,
                        referenceCount = 4,
                        maxDepth = 2,
                    ),
                ),
                metrics.graph(fqName = "lib.Foo", depth = 2),
            )
        }
    }

    @Test
    fun `serializes graph with stable node type names`() {
        val root = seededWorkspace()

        MetricsEngine(root).use { metrics ->
            val encoded = Json.encodeToString(MetricsGraph.serializer(), metrics.graph(fqName = "lib.Foo", depth = 1))

            assertTrue(encoded.contains("\"type\":\"SYMBOL\""))
            assertTrue(encoded.contains("\"edgeType\":\"REFERENCED_BY\""))
            assertTrue(encoded.contains("\"focalNodeId\":\"symbol:lib.Foo\""))
        }
    }

    @Test
    fun `returns empty metrics when index database does not exist`() {
        val root = workspaceRoot.toAbsolutePath().normalize()

        MetricsEngine(root).use { metrics ->
            assertTrue(metrics.fanInRanking(limit = 10).isEmpty())
            assertTrue(metrics.fanOutRanking(limit = 10).isEmpty())
            assertTrue(metrics.moduleCouplingMatrix().isEmpty())
            assertTrue(metrics.deadCodeCandidates().isEmpty())
            assertTrue(metrics.changeImpactRadius(fqName = "lib.Foo", depth = 2).isEmpty())
        }
    }

    @Test
    fun `searchSymbols returns empty when database is missing`() {
        val root = workspaceRoot.toAbsolutePath().normalize()
        MetricsEngine(root).use { metrics ->
            assertTrue(metrics.searchSymbols("foo").isEmpty())
        }
    }

    @Test
    fun `searchSymbols ranks popular symbols when query is blank`() {
        val root = seededWorkspace()
        MetricsEngine(root).use { metrics ->
            val results = metrics.searchSymbols(query = "  ", limit = 5)
            assertEquals("lib.Foo", results.first())
            assertTrue(results.contains("lib.Bar"))
            assertTrue(results.contains("app.A"))
        }
    }

    @Test
    fun `searchSymbols matches case-insensitively and ranks exact match first`() {
        val root = seededWorkspace()
        MetricsEngine(root).use { metrics ->
            val results = metrics.searchSymbols(query = "FOO", limit = 5)
            assertEquals("lib.Foo", results.first())
        }
    }

    @Test
    fun `searchSymbols rejects negative limit and returns empty for zero`() {
        val root = seededWorkspace()
        MetricsEngine(root).use { metrics ->
            assertTrue(metrics.searchSymbols("foo", limit = 0).isEmpty())
            try {
                metrics.searchSymbols("foo", limit = -1)
                assertTrue(false, "expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `returns empty metrics when database schema is not current`() {
        val root = workspaceRoot.toAbsolutePath().normalize()
        val dbPath = sourceIndexDatabasePath(root)
        Files.createDirectories(dbPath.parent)
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE schema_version (version INTEGER NOT NULL, generation INTEGER NOT NULL DEFAULT 0)")
                stmt.execute("INSERT INTO schema_version (version, generation) VALUES (999, 0)")
            }
        }

        MetricsEngine(root).use { metrics ->
            assertTrue(metrics.fanInRanking(limit = 10).isEmpty())
            assertTrue(metrics.fanOutRanking(limit = 10).isEmpty())
            assertTrue(metrics.moduleCouplingMatrix().isEmpty())
            assertTrue(metrics.deadCodeCandidates().isEmpty())
            assertTrue(metrics.changeImpactRadius(fqName = "lib.Foo", depth = 2).isEmpty())
        }
    }

    @Test
    fun `graph deduplicates sourceFileNodes when same path appears with multiple viaTargetFqNames`() {
        val root = workspaceRoot.toAbsolutePath().normalize()
        SqliteSourceIndexStore(root).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(
                    fileUpdate("/lib/Root.kt", identifiers = setOf("Root"), packageName = "lib", modulePath = ":lib", sourceSet = "main"),
                    fileUpdate("/app/A.kt", identifiers = setOf("A"), packageName = "app", modulePath = ":app", sourceSet = "main"),
                    fileUpdate("/app/B.kt", identifiers = setOf("B"), packageName = "app", modulePath = ":app", sourceSet = "main"),
                    fileUpdate("/app/D.kt", identifiers = setOf("D"), packageName = "app", modulePath = ":app", sourceSet = "main"),
                ),
                manifest = mapOf(
                    "/lib/Root.kt" to 1L,
                    "/app/A.kt" to 1L,
                    "/app/B.kt" to 1L,
                    "/app/D.kt" to 1L,
                ),
            )
            // A.kt and B.kt each reference lib.Root (depth=1 callers)
            store.upsertSymbolReference("/app/A.kt", 10, "lib.Root", "/lib/Root.kt", 1)
            store.upsertSymbolReference("/app/B.kt", 10, "lib.Root", "/lib/Root.kt", 1)
            // D.kt references both A.kt and B.kt symbols — two different viaTargetFqNames at depth=2
            store.upsertSymbolReference("/app/D.kt", 10, "app.A", "/app/A.kt", 1)
            store.upsertSymbolReference("/app/D.kt", 20, "app.B", "/app/B.kt", 1)
        }

        MetricsEngine(root).use { metrics ->
            val graph = metrics.graph(fqName = "lib.Root", depth = 2)

            val nodeIds = graph.nodes.map { it.id }
            // Each sourcePath must appear exactly once as a source-file node
            assertEquals(nodeIds.distinct(), nodeIds, "graph must not contain duplicate node IDs")

            val sourceFileNodes = graph.nodes.filter { it.type == MetricsGraphNodeType.FILE && it.id.startsWith("source-file:") }
            val sourceFilePaths = sourceFileNodes.map { it.name }
            assertEquals(sourceFilePaths.distinct(), sourceFilePaths, "duplicate sourceFileNodes found for same path")

            // /app/D.kt appears via both app.A and app.B — must produce exactly one source-file node
            assertEquals(1, sourceFileNodes.count { it.name == "/app/D.kt" })

            // both reference edge nodes for D.kt must still be present
            val dReferenceEdges = graph.nodes.filter { it.type == MetricsGraphNodeType.REFERENCE_EDGE && it.parentId == "source-file:/app/D.kt" }
            assertEquals(2, dReferenceEdges.size)
            assertTrue(dReferenceEdges.any { it.id == "via:app.A:/app/D.kt" })
            assertTrue(dReferenceEdges.any { it.id == "via:app.B:/app/D.kt" })

            // aggregated REFERENCED_BY edge weight for D.kt must be sum of both occurrences (1+1=2)
            val referencedByDEdge = graph.edges.filter {
                it.edgeType == MetricsGraphEdgeType.REFERENCED_BY && it.to == "source-file:/app/D.kt"
            }
            assertEquals(1, referencedByDEdge.size)
            assertEquals(2, referencedByDEdge.single().weight)
        }
    }

    private fun seededWorkspace(): Path {
        val root = workspaceRoot.toAbsolutePath().normalize()
        SqliteSourceIndexStore(root).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(
                    fileUpdate("/app/A.kt", identifiers = setOf("A"), packageName = "app", modulePath = ":app", sourceSet = "main"),
                    fileUpdate("/app/B.kt", identifiers = setOf("B"), packageName = "app", modulePath = ":app", sourceSet = "main"),
                    fileUpdate(
                        "/app/Unused.kt",
                        identifiers = setOf("Unused"),
                        packageName = "app",
                        modulePath = ":app",
                        sourceSet = "main",
                    ),
                    fileUpdate("/lib/Foo.kt", identifiers = setOf("Foo"), packageName = "lib", modulePath = ":lib", sourceSet = "main"),
                    fileUpdate("/lib/Bar.kt", identifiers = setOf("Bar"), packageName = "lib", modulePath = ":lib", sourceSet = "main"),
                ),
                manifest = mapOf(
                    "/app/A.kt" to 1L,
                    "/app/B.kt" to 1L,
                    "/app/Unused.kt" to 1L,
                    "/lib/Foo.kt" to 1L,
                    "/lib/Bar.kt" to 1L,
                ),
            )

            store.upsertSymbolReference("/app/A.kt", 10, "lib.Foo", "/lib/Foo.kt", 1)
            store.upsertSymbolReference("/app/A.kt", 20, "lib.Foo", "/lib/Foo.kt", 1)
            store.upsertSymbolReference("/app/A.kt", 30, "lib.Bar", "/lib/Bar.kt", 1)
            store.upsertSymbolReference("/app/B.kt", 10, "lib.Foo", "/lib/Foo.kt", 1)
            store.upsertSymbolReference("/app/B.kt", 20, "app.A", "/app/A.kt", 1)
            store.upsertSymbolReference("/app/C.kt", 10, "app.B", "/app/B.kt", 1)
        }
        return root
    }

    private fun fileUpdate(
        path: String,
        identifiers: Set<String>,
        packageName: String,
        modulePath: String,
        sourceSet: String?,
    ): FileIndexUpdate =
        FileIndexUpdate(
            path = path,
            identifiers = identifiers,
            packageName = packageName,
            modulePath = modulePath,
            sourceSet = sourceSet,
            imports = emptySet(),
            wildcardImports = emptySet(),
        )
}
