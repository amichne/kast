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
}
