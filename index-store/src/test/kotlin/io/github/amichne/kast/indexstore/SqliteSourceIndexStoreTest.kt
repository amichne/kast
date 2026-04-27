package io.github.amichne.kast.indexstore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class SqliteSourceIndexStoreTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `database is created under gradle kast cache directory`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
        }

        assertTrue(Files.isRegularFile(normalized.resolve(".gradle/kast/cache/source-index.db")))
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
                assertEquals(3, rs.getInt(1))
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
    fun `schema migration adds missing head commit column`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val cacheDir = kastCacheDirectory(normalized)
        Files.createDirectories(cacheDir)
        val dbPath = cacheDir.resolve("source-index.db")

        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE schema_version (version INTEGER NOT NULL, generation INTEGER NOT NULL DEFAULT 0)")
                stmt.execute("INSERT INTO schema_version (version, generation) VALUES (3, 0)")
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
                        module_name TEXT,
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
            }
        }

        SqliteSourceIndexStore(normalized).use { store ->
            assertTrue(store.ensureSchema())
            store.writeHeadCommit("def456")

            assertEquals("def456", store.readHeadCommit())
        }
    }

    @Test
    fun `source index snapshot round-trips identifiers and metadata`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(
                    FileIndexUpdate(
                        path = "/src/Caller.kt",
                        identifiers = setOf("Caller", "call"),
                        packageName = "consumer",
                        moduleName = ":app[main]",
                        imports = setOf("lib.Foo"),
                        wildcardImports = setOf("lib.internal"),
                    ),
                ),
                manifest = mapOf("/src/Caller.kt" to 123L),
            )

            val snapshot = store.loadSourceIndexSnapshot()

            assertEquals(listOf("/src/Caller.kt"), snapshot.candidatePathsByIdentifier.getValue("Caller"))
            assertEquals(":app[main]", snapshot.moduleNameByPath.getValue("/src/Caller.kt"))
            assertEquals("consumer", snapshot.packageByPath.getValue("/src/Caller.kt"))
            assertEquals(listOf("lib.Foo"), snapshot.importsByPath.getValue("/src/Caller.kt"))
            assertEquals(listOf("lib.internal"), snapshot.wildcardImportPackagesByPath.getValue("/src/Caller.kt"))
            assertEquals(mapOf("/src/Caller.kt" to 123L), store.loadManifest())
        }
    }

    @Test
    fun `symbol references round-trip and clear by source file`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.upsertSymbolReference(
                sourcePath = "/src/Caller.kt",
                sourceOffset = 42,
                targetFqName = "lib.Foo",
                targetPath = "/src/Foo.kt",
                targetOffset = 10,
            )
            store.upsertSymbolReference(
                sourcePath = "/src/Other.kt",
                sourceOffset = 7,
                targetFqName = "lib.Foo",
                targetPath = "/src/Foo.kt",
                targetOffset = 10,
            )

            assertEquals(2, store.referencesToSymbol("lib.Foo").size)
            store.clearReferencesFromFile("/src/Caller.kt")

            assertTrue(store.referencesFromFile("/src/Caller.kt").isEmpty())
            assertEquals(1, store.referencesToSymbol("lib.Foo").size)
        }
    }

    @Test
    fun `full source index rebuild clears stale symbol references`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(fileUpdate("/src/Caller.kt", "Caller")),
                manifest = mapOf("/src/Caller.kt" to 1L),
            )
            store.upsertSymbolReference(
                sourcePath = "/src/Caller.kt",
                sourceOffset = 1,
                targetFqName = "lib.Removed",
                targetPath = "/src/Removed.kt",
                targetOffset = 1,
            )

            store.saveFullIndex(
                updates = listOf(fileUpdate("/src/Other.kt", "Other")),
                manifest = mapOf("/src/Other.kt" to 2L),
            )

            assertTrue(store.referencesToSymbol("lib.Removed").isEmpty())
        }
    }

    @Test
    fun `removing a file clears inbound and outbound symbol references`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(fileUpdate("/src/Caller.kt", "Caller"), fileUpdate("/src/Target.kt", "Target")),
                manifest = mapOf("/src/Caller.kt" to 1L, "/src/Target.kt" to 1L),
            )
            store.upsertSymbolReference(
                sourcePath = "/src/Caller.kt",
                sourceOffset = 1,
                targetFqName = "demo.Target",
                targetPath = "/src/Target.kt",
                targetOffset = 1,
            )
            store.upsertSymbolReference(
                sourcePath = "/src/Target.kt",
                sourceOffset = 2,
                targetFqName = "demo.Other",
                targetPath = "/src/Other.kt",
                targetOffset = 1,
            )

            store.removeFile("/src/Target.kt")

            assertTrue(store.referencesToSymbol("demo.Target").isEmpty())
            assertTrue(store.referencesFromFile("/src/Target.kt").isEmpty())
        }
    }

    @Test
    fun `reference-only cleanup does not replace source index manifest`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(fileUpdate("/src/Caller.kt", "Caller")),
                manifest = mapOf("/src/Caller.kt" to 123L),
            )
            store.upsertSymbolReference(
                sourcePath = "/src/Stale.kt",
                sourceOffset = 1,
                targetFqName = "demo.Caller",
                targetPath = "/src/Caller.kt",
                targetOffset = 1,
            )

            store.removeReferencesOutsideSources(listOf("/src/Caller.kt"))

            assertEquals(mapOf("/src/Caller.kt" to 123L), store.loadManifest())
            assertTrue(store.referencesFromFile("/src/Stale.kt").isEmpty())
        }
    }

    private fun fileUpdate(path: String, identifier: String): FileIndexUpdate =
        FileIndexUpdate(
            path = path,
            identifiers = setOf(identifier),
            packageName = "demo",
            moduleName = ":main",
            imports = emptySet(),
            wildcardImports = emptySet(),
        )
}
