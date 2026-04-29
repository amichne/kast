package io.github.amichne.kast.indexstore

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
