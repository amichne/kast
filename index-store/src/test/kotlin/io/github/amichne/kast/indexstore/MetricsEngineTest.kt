package io.github.amichne.kast.indexstore

import io.github.amichne.kast.indexstore.api.graph.MetricsGraph
import io.github.amichne.kast.indexstore.api.index.FileIndexUpdate
import io.github.amichne.kast.indexstore.api.metrics.general.ConfidenceLevel
import io.github.amichne.kast.indexstore.api.metrics.general.SemanticBasis
import io.github.amichne.kast.indexstore.api.metrics.symbolquery.SymbolQueryFilters
import io.github.amichne.kast.indexstore.api.metrics.symbolquery.SymbolQueryGraphDirection
import io.github.amichne.kast.indexstore.api.reference.DeclarationKind
import io.github.amichne.kast.indexstore.api.reference.DeclarationRow
import io.github.amichne.kast.indexstore.api.reference.DeclarationVisibility
import io.github.amichne.kast.indexstore.api.reference.EdgeKind
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import io.github.amichne.kast.indexstore.metrics.MetricsEngine
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.store.cache.sourceIndexDatabasePath
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class MetricsEngineTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `lists declarations from the declaration registry`() {
        val root = seededWorkspace()

        MetricsEngine(root).use { metrics ->
            val declarations = metrics.declarations()

            assertTrue(declarations.any { it.fqName == "app.A" && it.kind == "CLASS" && it.visibility == "PUBLIC" })
            assertTrue(declarations.any { it.fqName == "app.unusedPrivate" && it.path == "/app/UnusedPrivate.kt" })
        }
    }

    @Test
    fun `ranks symbols by incoming references with edge kind breakdown`() {
        val root = seededWorkspace()

        MetricsEngine(root).use { metrics ->
            val foo = metrics.fanInRanking(limit = 2).first()

            assertEquals("lib.Foo", foo.targetFqName)
            assertEquals("/lib/Foo.kt", foo.targetPath)
            assertEquals(3, foo.occurrenceCount)
            assertEquals(mapOf("CALL" to 3), foo.byEdgeKind)
            assertEquals(SemanticBasis.K2_RESOLVED, foo.confidence.semanticBasis)
        }
    }

    @Test
    fun `ranks files by outgoing references with edge kind breakdown`() {
        val root = seededWorkspace()

        MetricsEngine(root).use { metrics ->
            val a = metrics.fanOutRanking(limit = 2).first()

            assertEquals("/app/A.kt", a.sourcePath)
            assertEquals(4, a.occurrenceCount)
            assertEquals(mapOf("CALL" to 3, "TYPE_REF" to 1), a.byEdgeKind)
        }
    }

    @Test
    fun `module coupling distinguishes public API references from internal leaks`() {
        val root = seededWorkspace()

        MetricsEngine(root).use { metrics ->
            val coupling = metrics.moduleCouplingMatrix().single()

            assertEquals(":app", coupling.sourceModulePath)
            assertEquals(":lib", coupling.targetModulePath)
            assertEquals(5, coupling.referenceCount)
            assertEquals(3, coupling.publicApiCount)
            assertEquals(2, coupling.internalLeakCount)
            assertEquals(mapOf("CALL" to 4, "TYPE_REF" to 1), coupling.byEdgeKind)
        }
    }

    @Test
    fun `module boundary reports exported and consumed symbols`() {
        val root = seededWorkspace()

        MetricsEngine(root).use { metrics ->
            val boundary = metrics.moduleBoundary(":app").single()

            assertEquals(":app", boundary.modulePath)
            assertEquals(4, boundary.exportedSymbolCount)
            assertEquals(3, boundary.consumedSymbolCount)
            assertEquals(3, boundary.publicApiReferences)
            assertEquals(2, boundary.internalLeakReferences)
        }
    }

    @Test
    fun `api surface aggregates declarations per module`() {
        val root = seededWorkspace()

        MetricsEngine(root).use { metrics ->
            val app = metrics.apiSurface(":app").single()

            assertEquals(4, app.publicSymbolCount)
            assertEquals(0, app.internalSymbolCount)
            assertEquals(1, app.privateSymbolCount)
            assertEquals(5, app.totalSymbolCount)
            assertEquals(0.2, app.encapsulationRatio)
        }
    }

    @Test
    fun `dead code candidates use declaration visibility for confidence`() {
        val root = seededWorkspace()

        MetricsEngine(root).use { metrics ->
            val candidates = metrics.deadCodeCandidates()
            val privateCandidate = candidates.single { it.fqName == "app.unusedPrivate" }
            val publicCandidate = candidates.single { it.fqName == "app.PublicUnused" }

            assertEquals(ConfidenceLevel.HIGH, privateCandidate.confidence.level)
            assertEquals(ConfidenceLevel.MEDIUM, publicCandidate.confidence.level)
            assertEquals("PRIVATE", privateCandidate.visibility)
            assertFalse(candidates.any { it.fqName == "lib.Foo" })
        }
    }

    @Test
    fun `impact radius walks symbol level edges when source symbols are available`() {
        val root = seededWorkspace()

        MetricsEngine(root).use { metrics ->
            val impact = metrics.changeImpactRadius(fqName = "lib.Foo", depth = 2)

            assertTrue(impact.any { it.sourcePath == "/app/A.kt" && it.depth == 1 && it.viaTargetFqName == "lib.Foo" && it.occurrenceCount == 2 })
            assertTrue(impact.any { it.sourcePath == "/app/B.kt" && it.depth == 1 && it.viaTargetFqName == "lib.Foo" && it.occurrenceCount == 1 })
            assertTrue(impact.any { it.sourcePath == "/app/C.kt" && it.depth == 2 && it.viaTargetFqName == "app.B" })
        }
    }

    @Test
    fun `confidence envelope reflects declaration basis and index completeness`() {
        val root = seededWorkspace()

        MetricsEngine(root).use { metrics ->
            val confidence = metrics.fanInRanking(limit = 1).single().confidence

            assertEquals(SemanticBasis.K2_RESOLVED, confidence.semanticBasis)
            assertEquals(ConfidenceLevel.SPECULATIVE, confidence.level)
            assertEquals(3.0 / 7.0, confidence.indexCompleteness)
        }
    }

    @Test
    fun `module depth uses declarations for declared symbol count`() {
        val root = seededWorkspace()

        MetricsEngine(root).use { metrics ->
            val app = metrics.moduleDepthMetrics().single { it.modulePath == ":app" }

            assertEquals(5, app.fileCount)
            assertEquals(5, app.declaredSymbolCount)
            assertEquals(2, app.internalRefCount)
            assertEquals(5, app.externalRefCount)
        }
    }

    @Test
    fun `graph keeps stable serialized node type names`() {
        val root = seededWorkspace()

        MetricsEngine(root).use { metrics ->
            val encoded = Json.encodeToString(MetricsGraph.serializer(), metrics.graph(fqName = "lib.Foo", depth = 1))

            assertTrue(encoded.contains("\"type\":\"SYMBOL\""))
            assertTrue(encoded.contains("\"edgeType\":\"REFERENCED_BY\""))
            assertTrue(encoded.contains("\"focalNodeId\":\"symbol:lib.Foo\""))
        }
    }

    @Test
    fun `searchSymbols ranks popular symbols when query is blank`() {
        val root = seededWorkspace()

        MetricsEngine(root).use { metrics ->
            val results = metrics.searchSymbols(query = "  ", limit = 5)

            assertEquals("lib.Foo", results.first())
            assertTrue(results.contains("lib.Bar"))
            assertTrue(results.contains("lib.InternalApi"))
        }
    }

    @Test
    fun `symbol query declarations preserve exact and lexical evidence`() {
        val root = seededWorkspace()

        MetricsEngine(root).use { metrics ->
            val exact = metrics.symbolQueryDeclarations(query = "lib.Foo", limit = 5)
            val lexical = metrics.symbolQueryDeclarations(query = "Internal", limit = 5)

            assertEquals("lib.Foo", exact.first().fqName)
            assertTrue(exact.first().exactMatches.any { it.field == "fq_names.fq_name" && it.matchType == "EQUALS" })
            assertTrue(lexical.any { result ->
                result.fqName == "lib.InternalApi" &&
                    result.lexicalMatches.any { it.field == "fq_names.fq_name" && it.term == "Internal" }
            })
        }
    }

    @Test
    fun `symbol query filters are enforced from indexed facts`() {
        val root = seededWorkspace()

        MetricsEngine(root).use { metrics ->
            val filtered = metrics.symbolQueryByFilters(
                filters = SymbolQueryFilters(
                    kinds = setOf("FUNCTION"),
                    visibility = setOf("INTERNAL"),
                    modulePath = ":lib",
                    sourceSet = "main",
                    fileGlob = "*/Bar.kt",
                    packagePrefix = "lib",
                    fqNamePrefix = "lib",
                ),
                limit = 10,
            )

            assertEquals(listOf("lib.Bar", "lib.InternalApi"), filtered.map { it.fqName })
            assertTrue(filtered.all { it.kind == "FUNCTION" && it.visibility == "INTERNAL" && it.modulePath == ":lib" })
            assertTrue(filtered.all { result -> result.structuralConstraints.all { it.source == "sqlite" } })
        }
    }

    @Test
    fun `symbol query graph edges honor direction edge kinds and declaration supertypes`() {
        val root = seededWorkspace()

        MetricsEngine(root).use { metrics ->
            val foo = metrics.symbolQueryDeclarations(query = "lib.Foo", limit = 1).single()
            val incomingCalls = metrics.symbolQueryGraphEdges(
                startFqIds = setOf(foo.fqId),
                direction = SymbolQueryGraphDirection.INCOMING,
                edgeKinds = setOf(EdgeKind.CALL),
                depth = 1,
                maxEdgesPerResult = 10,
            )
            val incomingInheritance = metrics.symbolQueryGraphEdges(
                startFqIds = setOf(foo.fqId),
                direction = SymbolQueryGraphDirection.INCOMING,
                edgeKinds = setOf(EdgeKind.INHERITANCE),
                depth = 1,
                maxEdgesPerResult = 10,
            )
            val appA = metrics.symbolQueryDeclarations(query = "app.A", limit = 1).single()
            val outgoingTypeRefs = metrics.symbolQueryGraphEdges(
                startFqIds = setOf(appA.fqId),
                direction = SymbolQueryGraphDirection.OUTGOING,
                edgeKinds = setOf(EdgeKind.TYPE_REF),
                depth = 1,
                maxEdgesPerResult = 10,
            )

            assertTrue(incomingCalls.all { it.edgeKind == "CALL" && it.toFqName == "lib.Foo" })
            assertTrue(incomingCalls.any { it.fromFqName == "app.A" && it.sourceFile == "/app/A.kt" && it.sourceOffset == 10 })
            assertEquals(listOf("app.C"), incomingInheritance.mapNotNull { it.fromFqName })
            assertEquals(listOf("lib.Bar"), outgoingTypeRefs.map { it.toFqName })
        }
    }

    @Test
    fun `symbol query declarations carry confidence from the source index`() {
        val root = seededWorkspace()

        MetricsEngine(root).use { metrics ->
            val result = metrics.symbolQueryDeclarations(query = "lib.Foo", limit = 1).single()

            assertEquals(SemanticBasis.K2_RESOLVED, result.confidence.semanticBasis)
            assertEquals(ConfidenceLevel.SPECULATIVE, result.confidence.level)
            assertEquals(3.0 / 7.0, result.confidence.indexCompleteness)
        }
    }

    @Test
    fun `returns empty metrics when index database does not exist`() {
        val root = workspaceRoot.toAbsolutePath().normalize()

        MetricsEngine(root).use { metrics ->
            assertTrue(metrics.fanInRanking(limit = 10).isEmpty())
            assertTrue(metrics.declarations().isEmpty())
            assertTrue(metrics.deadCodeCandidates().isEmpty())
            assertTrue(metrics.changeImpactRadius(fqName = "lib.Foo", depth = 2).isEmpty())
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
            assertTrue(metrics.apiSurface().isEmpty())
            assertTrue(metrics.moduleBoundary().isEmpty())
            assertTrue(metrics.deadCodeCandidates().isEmpty())
        }
    }

    private fun seededWorkspace(): Path {
        val root = workspaceRoot.toAbsolutePath().normalize()
        SqliteSourceIndexStore(root).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(
                    fileUpdate("/app/A.kt", identifiers = setOf("A"), packageName = "app", modulePath = ":app"),
                    fileUpdate("/app/B.kt", identifiers = setOf("B"), packageName = "app", modulePath = ":app"),
                    fileUpdate("/app/C.kt", identifiers = setOf("C"), packageName = "app", modulePath = ":app"),
                    fileUpdate("/app/UnusedPrivate.kt", identifiers = setOf("unusedPrivate"), packageName = "app", modulePath = ":app"),
                    fileUpdate("/app/PublicUnused.kt", identifiers = setOf("PublicUnused"), packageName = "app", modulePath = ":app"),
                    fileUpdate("/lib/Foo.kt", identifiers = setOf("Foo"), packageName = "lib", modulePath = ":lib"),
                    fileUpdate("/lib/Bar.kt", identifiers = setOf("Bar"), packageName = "lib", modulePath = ":lib"),
                ),
                manifest = mapOf(
                    "/app/A.kt" to 1L,
                    "/app/B.kt" to 1L,
                    "/app/C.kt" to 1L,
                    "/app/UnusedPrivate.kt" to 1L,
                    "/app/PublicUnused.kt" to 1L,
                    "/lib/Foo.kt" to 1L,
                    "/lib/Bar.kt" to 1L,
                ),
            )
            store.replaceDeclarationsFromFiles(
                listOf(
                    "/app/A.kt" to listOf(declaration("app.A", DeclarationKind.CLASS, DeclarationVisibility.PUBLIC, "/app/A.kt", ":app")),
                    "/app/B.kt" to listOf(declaration("app.B", DeclarationKind.CLASS, DeclarationVisibility.PUBLIC, "/app/B.kt", ":app")),
                    "/app/C.kt" to listOf(
                        declaration("app.C", DeclarationKind.CLASS, DeclarationVisibility.PUBLIC, "/app/C.kt", ":app", supertypes = listOf("lib.Foo")),
                    ),
                    "/app/UnusedPrivate.kt" to listOf(
                        declaration("app.unusedPrivate", DeclarationKind.PROPERTY, DeclarationVisibility.PRIVATE, "/app/UnusedPrivate.kt", ":app"),
                    ),
                    "/app/PublicUnused.kt" to listOf(
                        declaration("app.PublicUnused", DeclarationKind.FUNCTION, DeclarationVisibility.PUBLIC, "/app/PublicUnused.kt", ":app"),
                    ),
                    "/lib/Foo.kt" to listOf(declaration("lib.Foo", DeclarationKind.CLASS, DeclarationVisibility.PUBLIC, "/lib/Foo.kt", ":lib")),
                    "/lib/Bar.kt" to listOf(
                        declaration("lib.Bar", DeclarationKind.FUNCTION, DeclarationVisibility.INTERNAL, "/lib/Bar.kt", ":lib"),
                        declaration("lib.InternalApi", DeclarationKind.FUNCTION, DeclarationVisibility.INTERNAL, "/lib/Bar.kt", ":lib"),
                    ),
                ),
            )
            store.replaceReferencesFromFiles(
                listOf(
                    "/app/A.kt" to listOf(
                        reference("/app/A.kt", 10, "app.A", "lib.Foo", "/lib/Foo.kt", EdgeKind.CALL),
                        reference("/app/A.kt", 20, "app.A", "lib.Foo", "/lib/Foo.kt", EdgeKind.CALL),
                        reference("/app/A.kt", 30, "app.A", "lib.Bar", "/lib/Bar.kt", EdgeKind.TYPE_REF),
                        reference("/app/A.kt", 40, "app.A", "lib.InternalApi", "/lib/Bar.kt", EdgeKind.CALL),
                    ),
                    "/app/B.kt" to listOf(
                        reference("/app/B.kt", 10, "app.B", "lib.Foo", "/lib/Foo.kt", EdgeKind.CALL),
                        reference("/app/B.kt", 20, "app.B", "app.A", "/app/A.kt", EdgeKind.CALL),
                    ),
                    "/app/C.kt" to listOf(
                        reference("/app/C.kt", 10, "app.C", "app.B", "/app/B.kt", EdgeKind.CALL),
                    ),
                ),
            )
        }
        return root
    }

    private fun fileUpdate(
        path: String,
        identifiers: Set<String>,
        packageName: String,
        modulePath: String,
    ): FileIndexUpdate =
        FileIndexUpdate(
            path = path,
            identifiers = identifiers,
            packageName = packageName,
            modulePath = modulePath,
            sourceSet = "main",
            imports = emptySet(),
            wildcardImports = emptySet(),
        )

    private fun declaration(
        fqName: String,
        kind: DeclarationKind,
        visibility: DeclarationVisibility,
        filePath: String,
        modulePath: String,
        supertypes: List<String> = emptyList(),
    ): DeclarationRow =
        DeclarationRow(
            fqName = fqName,
            kind = kind,
            visibility = visibility,
            filePath = filePath,
            declarationOffset = 1,
            modulePath = modulePath,
            sourceSet = "main",
            supertypes = supertypes,
        )

    private fun reference(
        sourcePath: String,
        sourceOffset: Int,
        sourceFqName: String,
        targetFqName: String,
        targetPath: String,
        edgeKind: EdgeKind,
    ): SymbolReferenceRow =
        SymbolReferenceRow(
            sourcePath = sourcePath,
            sourceOffset = sourceOffset,
            sourceFqName = sourceFqName,
            targetFqName = targetFqName,
            targetPath = targetPath,
            targetOffset = 1,
            edgeKind = edgeKind,
        )
}
