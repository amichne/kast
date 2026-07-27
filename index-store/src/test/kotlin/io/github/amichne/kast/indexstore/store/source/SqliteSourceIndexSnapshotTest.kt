package io.github.amichne.kast.indexstore

import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.indexstore.api.reference.ExactReferenceTarget
import io.github.amichne.kast.indexstore.api.index.BuildQualifiedGradleProjectIdentity
import io.github.amichne.kast.indexstore.api.index.BuildQualifiedGradleSourceSetIdentity
import io.github.amichne.kast.indexstore.api.index.FileIndexUpdate
import io.github.amichne.kast.indexstore.api.index.GradleProjectPath
import io.github.amichne.kast.indexstore.api.index.GradleSourceSetName
import io.github.amichne.kast.indexstore.api.index.IndexedPackageEvidence
import io.github.amichne.kast.indexstore.api.index.IndexedPackageUnprovenReason
import io.github.amichne.kast.indexstore.api.index.WorkspaceRelativeGradleBuildRoot
import io.github.amichne.kast.indexstore.store.SOURCE_INDEX_SCHEMA_VERSION
import io.github.amichne.kast.indexstore.store.SourceIndexPageReadObserver
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.store.cache.kastCacheDirectory
import io.github.amichne.kast.indexstore.store.cache.sourceIndexDatabasePath
import io.github.amichne.kast.indexstore.snapshot.GitObjectId
import io.github.amichne.kast.indexstore.snapshot.ProducerVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class SqliteSourceIndexSnapshotTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `source index snapshot round-trips identifiers and metadata`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val callerPath = normalized.resolve("src/Caller.kt").toString()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(
                    FileIndexUpdate(
                        path = callerPath,
                        identifiers = setOf("Caller", "call"),
                        packageName = "consumer",
                        modulePath = ":app",
                        sourceSet = "main",
                        imports = setOf("lib.Foo"),
                        wildcardImports = setOf("lib.internal"),
                        packageEvidence = IndexedPackageEvidence.ProvenNamed(
                            IndexedPackageEvidence.CanonicalName.parse("consumer"),
                        ),
                    ),
                ),
                manifest = mapOf(callerPath to 123L),
            )

            val snapshot = store.loadSourceIndexSnapshot()


            assertEquals(listOf(callerPath), snapshot.candidatePathsByIdentifier.getValue("Caller"))
            assertEquals(":app[main]", snapshot.moduleNameByPath.getValue(callerPath))
            assertEquals("consumer", snapshot.packageByPath.getValue(callerPath))
            assertEquals(listOf("lib.Foo"), snapshot.importsByPath.getValue(callerPath))
            assertEquals(listOf("lib.internal"), snapshot.wildcardImportPackagesByPath.getValue(callerPath))
            assertEquals(mapOf(callerPath to 123L), store.loadManifest())
        }
    }

    @Test
    fun `source index stores interned directory prefixes while returning absolute paths`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val callerPath = normalized.resolve("src/main/Caller.kt").toString()
        val targetPath = normalized.resolve("src/test/Target.kt").toString()

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(
                    fileUpdate(callerPath, "Caller"),
                    fileUpdate(targetPath, "Target"),
                ),
                manifest = mapOf(callerPath to 1L, targetPath to 2L),
            )

            val snapshot = store.loadSourceIndexSnapshot()

            assertEquals(listOf(callerPath), snapshot.candidatePathsByIdentifier.getValue("Caller"))
            assertEquals(listOf(targetPath), snapshot.candidatePathsByIdentifier.getValue("Target"))
            assertEquals(mapOf(callerPath to 1L, targetPath to 2L), store.loadManifest())
        }

        DriverManager.getConnection("jdbc:sqlite:${sourceIndexDatabasePath(normalized)}").use { conn ->
            conn.prepareStatement("SELECT dir_path FROM path_prefixes ORDER BY dir_path").use { stmt ->
                val rs = stmt.executeQuery()
                val prefixes = buildList {
                    while (rs.next()) add(rs.getString(1))
                }
                assertEquals(listOf("src/main", "src/test"), prefixes)
            }
            conn.prepareStatement("PRAGMA table_info(identifier_paths)").use { stmt ->
                val rs = stmt.executeQuery()
                val columns = buildList {
                    while (rs.next()) add(rs.getString("name"))
                }
                assertFalse("path" in columns)
                assertTrue("prefix_id" in columns)
                assertTrue("filename" in columns)
            }
        }
    }

    @Test
    fun `source index stores FQ names and imports in interned relational tables`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val callerPath = normalized.resolve("src/Caller.kt").toString()
        val targetPath = normalized.resolve("src/Foo.kt").toString()

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(
                    FileIndexUpdate(
                        path = callerPath,
                        identifiers = setOf("Caller"),
                        packageName = "consumer",
                        modulePath = ":app",
                        sourceSet = "main",
                        imports = setOf("lib.Foo", "kotlin.collections.List"),
                        wildcardImports = setOf("lib.internal"),
                        packageEvidence = IndexedPackageEvidence.ProvenNamed(
                            IndexedPackageEvidence.CanonicalName.parse("consumer"),
                        ),
                    ),
                ),
                manifest = mapOf(callerPath to 1L),
            )
            store.upsertSymbolReference(callerPath, 42, "lib.Foo", targetPath, 10)

            val snapshot = store.loadSourceIndexSnapshot()

            assertEquals("consumer", snapshot.packageByPath.getValue(callerPath))
            assertEquals(listOf("kotlin.collections.List", "lib.Foo"), snapshot.importsByPath.getValue(callerPath))
            assertEquals(listOf("lib.internal"), snapshot.wildcardImportPackagesByPath.getValue(callerPath))
            assertEquals("lib.Foo", store.referencesToSymbol("lib.Foo").single().targetFqName)
        }

        DriverManager.getConnection("jdbc:sqlite:${sourceIndexDatabasePath(normalized)}").use { conn ->
            assertTableColumns(
                conn = conn,
                tableName = "file_metadata",
                present = setOf(
                    "prefix_id",
                    "filename",
                    "package_fq_id",
                    "package_state",
                    "package_unproven_reason",
                    "module_path",
                    "source_set",
                ),
                absent = setOf("path", "package_name", "module_name", "imports", "wildcard_imports"),
            )
            assertTableColumns(
                conn = conn,
                tableName = "symbol_references",
                present = setOf("src_prefix_id", "src_filename", "target_fq_id"),
                absent = setOf("source_path", "target_path", "target_fq_name"),
            )
            conn.prepareStatement(
                """SELECT fq.fq_name
                   FROM file_imports imports
                   JOIN fq_names fq ON fq.fq_id = imports.fq_id
                   ORDER BY fq.fq_name""",
            ).use { stmt ->
                val rs = stmt.executeQuery()
                val imports = buildList {
                    while (rs.next()) add(rs.getString(1))
                }
                assertEquals(listOf("kotlin.collections.List", "lib.Foo"), imports)
            }
            conn.prepareStatement(
                """SELECT fq.fq_name
                   FROM file_wildcard_imports imports
                   JOIN fq_names fq ON fq.fq_id = imports.fq_id""",
            ).use { stmt ->
                val rs = stmt.executeQuery()
                assertTrue(rs.next())
                assertEquals("lib.internal", rs.getString(1))
            }
        }
    }

    @Test
    fun `restored source index decodes workspace paths under current workspace root`() {
        val originalRoot = workspaceRoot.resolve("original").toAbsolutePath().normalize()
        val restoredRoot = workspaceRoot.resolve("restored").toAbsolutePath().normalize()
        val originalPath = originalRoot.resolve("src/Portable.kt").toString()
        val restoredPath = restoredRoot.resolve("src/Portable.kt").toString()

        SqliteSourceIndexStore(originalRoot).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(fileUpdate(originalPath, "Portable")),
                manifest = mapOf(originalPath to 9L),
            )
        }
        copySourceIndexDatabase(originalRoot, restoredRoot)

        SqliteSourceIndexStore(restoredRoot).use { store ->
            assertTrue(store.ensureSchema())

            assertEquals(
                listOf(restoredPath),
                store.loadSourceIndexSnapshot().candidatePathsByIdentifier.getValue("Portable")
            )
            assertEquals(mapOf(restoredPath to 9L), store.loadManifest())
        }
    }

    @Test
    fun `paths outside workspace root round-trip through absolute sentinel prefix`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val externalPath = normalized.parent.resolve("external/Outside.kt").normalize().toString()

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(fileUpdate(externalPath, "Outside")),
                manifest = mapOf(externalPath to 4L),
            )

            assertEquals(
                listOf(externalPath),
                store.loadSourceIndexSnapshot().candidatePathsByIdentifier.getValue("Outside")
            )
            assertEquals(mapOf(externalPath to 4L), store.loadManifest())
        }

        DriverManager.getConnection("jdbc:sqlite:${sourceIndexDatabasePath(normalized)}").use { conn ->
            conn.prepareStatement("SELECT dir_path FROM path_prefixes").use { stmt ->
                val rs = stmt.executeQuery()
                val prefixes = buildList {
                    while (rs.next()) add(rs.getString(1))
                }
                assertTrue(prefixes.any { it.startsWith("__kast_abs__/") })
            }
        }
    }

    @Test
    fun `incremental file indexing adds new prefixes to table and cache`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val firstPath = normalized.resolve("first/One.kt").toString()
        val secondPath = normalized.resolve("second/Two.kt").toString()

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFileIndex(fileUpdate(firstPath, "One"))
            store.saveFileIndex(fileUpdate(secondPath, "Two"))

            val snapshot = store.loadSourceIndexSnapshot()

            assertEquals(listOf(firstPath), snapshot.candidatePathsByIdentifier.getValue("One"))
            assertEquals(listOf(secondPath), snapshot.candidatePathsByIdentifier.getValue("Two"))
        }

        DriverManager.getConnection("jdbc:sqlite:${sourceIndexDatabasePath(normalized)}").use { conn ->
            conn.prepareStatement("SELECT dir_path FROM path_prefixes ORDER BY dir_path").use { stmt ->
                val rs = stmt.executeQuery()
                val prefixes = buildList {
                    while (rs.next()) add(rs.getString(1))
                }
                assertEquals(listOf("first", "second"), prefixes)
            }

        }
    }

}
