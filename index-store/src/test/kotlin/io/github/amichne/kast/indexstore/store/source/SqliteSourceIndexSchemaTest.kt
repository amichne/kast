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

class SqliteSourceIndexSchemaTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `database is created under workspace cache directory`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
        }

        assertTrue(Files.isRegularFile(sourceIndexDatabasePath(normalized)))
        assertTrue(sourceIndexDatabasePath(normalized).startsWith(kastCacheDirectory(normalized)))
    }

    @Test
    fun `different workspace roots use different source index databases`() {
        val firstRoot = Files.createDirectories(workspaceRoot.resolve("first")).toAbsolutePath().normalize()
        val secondRoot = Files.createDirectories(workspaceRoot.resolve("second")).toAbsolutePath().normalize()

        SqliteSourceIndexStore(firstRoot).use { store -> store.ensureSchema() }
        SqliteSourceIndexStore(secondRoot).use { store -> store.ensureSchema() }

        val firstDatabase = sourceIndexDatabasePath(firstRoot)
        val secondDatabase = sourceIndexDatabasePath(secondRoot)
        assertNotEquals(firstDatabase, secondDatabase)
        assertTrue(Files.isRegularFile(firstDatabase), "first database missing at $firstDatabase")
        assertTrue(Files.isRegularFile(secondDatabase), "second database missing at $secondDatabase")
    }

    @Test
    fun `ensureSchema bootstraps sqlite driver when DriverManager registry is empty`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()

        withSqliteDriversDeregistered {
            SqliteSourceIndexStore(normalized).use { store ->
                assertTrue(store.ensureSchema())
            }
        }

        assertTrue(Files.isRegularFile(sourceIndexDatabasePath(normalized)))
    }

    @Test
    fun `schema version mismatch triggers full rebuild`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val cacheDir = kastCacheDirectory(normalized)
        Files.createDirectories(cacheDir)
        val dbPath = cacheDir.resolve("source-index.db")

        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE schema_version (version INTEGER NOT NULL, generation INTEGER NOT NULL DEFAULT 0)")
                stmt.execute("INSERT INTO schema_version (version, generation) VALUES (999, 0)")
            }
        }

        SqliteSourceIndexStore(normalized).use { store ->
            assertFalse(store.ensureSchema())
        }

        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.prepareStatement("SELECT version FROM schema_version LIMIT 1").use { stmt ->
                val rs = stmt.executeQuery()
                assertTrue(rs.next())
                assertEquals(SOURCE_INDEX_SCHEMA_VERSION, rs.getInt(1))
            }
        }
    }

    @Test
    fun `ensureSchema reloads interning tables after database replacement`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val dbPath = sourceIndexDatabasePath(normalized)
        val oldPackage = IndexedPackageEvidence.CanonicalName.parse("old.pkg")
        val freshPackage = IndexedPackageEvidence.CanonicalName.parse("fresh.pkg")

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFileIndex(
                fileUpdate(normalized.resolve("old/Before.kt").toString(), "Before").copy(
                    packageName = oldPackage.value,
                    packageEvidence = IndexedPackageEvidence.ProvenNamed(oldPackage),
                ),
            )

            assertTrue(Files.deleteIfExists(dbPath))
            assertTrue(store.ensureSchema())
            store.saveFileIndex(
                fileUpdate(normalized.resolve("fresh/Fresh.kt").toString(), "Fresh").copy(
                    packageName = freshPackage.value,
                    packageEvidence = IndexedPackageEvidence.ProvenNamed(freshPackage),
                ),
            )

            val afterPath = normalized.resolve("old/After.kt").toString()
            store.saveFileIndex(
                fileUpdate(afterPath, "After").copy(
                    packageName = oldPackage.value,
                    packageEvidence = IndexedPackageEvidence.ProvenNamed(oldPackage),
                ),
            )

            assertEquals(
                IndexedPackageEvidence.ProvenNamed(oldPackage),
                store.packageEvidenceForFile(afterPath),
            )
        }
    }

    @Test
    fun `interning caches refresh after another store commits`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val beforePath = normalized.resolve("before/Before.kt").toString()
        val afterPath = normalized.resolve("after/After.kt").toString()
        val targetPath = normalized.resolve("target/Target.kt").toString()
        val afterTargetPath = normalized.resolve("after-target/Target.kt").toString()
        val pendingPath = normalized.resolve("pending/Removed.kt").toString()

        SqliteSourceIndexStore(normalized).use { reader ->
            reader.ensureSchema()
            reader.upsertSymbolReference(
                sourcePath = beforePath,
                sourceOffset = 1,
                targetFqName = "demo.Target",
                targetPath = targetPath,
                targetOffset = 1,
                sourceFqName = "demo.Before",
            )
            assertEquals(listOf(beforePath), reader.referencesToSymbol("demo.Target").map { it.sourcePath })

            SqliteSourceIndexStore(normalized).use { writer ->
                writer.ensureSchema()
                writer.upsertSymbolReference(
                    sourcePath = afterPath,
                    sourceOffset = 2,
                    targetFqName = "demo.AfterTarget",
                    targetPath = afterTargetPath,
                    targetOffset = 1,
                    sourceFqName = "demo.After",
                )

                val reference = reader.referencesToSymbol("demo.AfterTarget").single()
                assertEquals(afterPath, reference.sourcePath)
                assertEquals("demo.After", reference.sourceFqName)
                assertEquals("demo.AfterTarget", reference.targetFqName)
                assertEquals(afterTargetPath, reference.targetPath)

                val generationBeforePendingUpdate = writer.readGeneration()
                writer.appendPendingUpdate("remove_file", pendingPath, payload = null)
                assertEquals(generationBeforePendingUpdate, writer.readGeneration())
                assertEquals(1, reader.reconcilePendingUpdates())
            }
        }
    }

    @Test
    fun `head commit round-trips through schema version table`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()

            store.writeHeadCommit("abc123")

            assertEquals("abc123", store.readHeadCommit())
        }
    }

    @Test
    fun `schema creates persistent trigram FTS for FQ names`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        SqliteSourceIndexStore(normalized).use { store -> store.ensureSchema() }

        DriverManager.getConnection("jdbc:sqlite:${sourceIndexDatabasePath(normalized)}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO fq_names(fq_id, fq_name) VALUES (1, 'com.example.FooWidget')")
            }

            assertEquals(listOf("com.example.FooWidget"), ftsMatches(conn, "Widget"))

            conn.createStatement().use { stmt ->
                stmt.execute("UPDATE fq_names SET fq_name = 'com.example.BarWidget' WHERE fq_id = 1")
            }

            assertEquals(emptyList<String>(), ftsMatches(conn, "FooWidget"))
            assertEquals(listOf("com.example.BarWidget"), ftsMatches(conn, "BarWidget"))

            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM fq_names WHERE fq_id = 1")
            }

            assertEquals(emptyList<String>(), ftsMatches(conn, "BarWidget"))
        }
    }

    @Test
    fun `prior schema rebuilds without preserving compatibility data`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val cacheDir = kastCacheDirectory(normalized)
        Files.createDirectories(cacheDir)
        val dbPath = cacheDir.resolve("source-index.db")

        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE schema_version (version INTEGER NOT NULL, generation INTEGER NOT NULL DEFAULT 0)")
                stmt.execute("INSERT INTO schema_version (version, generation) VALUES (4, 0)")
                stmt.execute(
                    """CREATE TABLE identifier_paths (
                        identifier TEXT NOT NULL,
                        path TEXT NOT NULL,
                        PRIMARY KEY (identifier, path)
                    )""",
                )
                stmt.execute(
                    """CREATE TABLE file_metadata (
                        path TEXT PRIMARY KEY,
                        package_name TEXT,
                        module_path TEXT,
                        source_set TEXT,
                        imports TEXT,
                        wildcard_imports TEXT
                    )""",
                )
                stmt.execute(
                    """CREATE TABLE file_manifest (
                        path TEXT PRIMARY KEY,
                        last_modified_millis INTEGER NOT NULL
                    )""",
                )
                stmt.execute(
                    """CREATE TABLE workspace_discovery (
                        cache_key TEXT PRIMARY KEY,
                        schema_version INTEGER NOT NULL,
                        payload TEXT NOT NULL
                    )""",
                )
                stmt.execute("INSERT INTO workspace_discovery (cache_key, schema_version, payload) VALUES ('modules', 1, '{}')")
            }
        }

        SqliteSourceIndexStore(normalized).use { store ->
            assertFalse(store.ensureSchema())
            store.writeHeadCommit("def456")

            assertEquals("def456", store.readHeadCommit())
            assertNull(store.readWorkspaceDiscovery("modules"))
            assertSchemaUsesInternedPaths(dbPath)
        }
    }

}
