package io.github.amichne.kast.indexstore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
                assertEquals(4, rs.getInt(1))
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
            assertTrue(store.ensureSchema())
            store.writeHeadCommit("def456")

            assertEquals("def456", store.readHeadCommit())
            assertEquals("{}", store.readWorkspaceDiscovery("modules"))
            assertSchemaUsesInternedPaths(dbPath)
        }
    }

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
                        moduleName = ":app[main]",
                        imports = setOf("lib.Foo"),
                        wildcardImports = setOf("lib.internal"),
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
                        moduleName = ":app[main]",
                        imports = setOf("lib.Foo", "kotlin.collections.List"),
                        wildcardImports = setOf("lib.internal"),
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
                present = setOf("prefix_id", "filename", "package_fq_id", "module_name"),
                absent = setOf("path", "package_name", "imports", "wildcard_imports"),
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
    fun `pending update reconciliation applies only latest file state and marks prior rows applied`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val path = normalized.resolve("src/Pending.kt").toString()

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.appendPendingUpdate(
                op = "upsert_file",
                path = path,
                payload = """{"identifiers":["OldName"],"packageName":"old.pkg","moduleName":":old","imports":["old.Import"],"wildcardImports":[]}""",
                sessionId = "session-1",
            )
            store.appendPendingUpdate(
                op = "upsert_file",
                path = path,
                payload = """{"identifiers":["NewName"],"packageName":"new.pkg","moduleName":":new","imports":["new.Import"],"wildcardImports":["new.wild"]}""",
                sessionId = "session-2",
            )

            assertEquals(1, store.reconcilePendingUpdates())

            val snapshot = store.loadSourceIndexSnapshot()
            assertFalse(snapshot.candidatePathsByIdentifier.containsKey("OldName"))
            assertEquals(listOf(path), snapshot.candidatePathsByIdentifier.getValue("NewName"))
            assertEquals("new.pkg", snapshot.packageByPath.getValue(path))
            assertEquals(listOf("new.Import"), snapshot.importsByPath.getValue(path))
            assertEquals(listOf("new.wild"), snapshot.wildcardImportPackagesByPath.getValue(path))
        }

        DriverManager.getConnection("jdbc:sqlite:${sourceIndexDatabasePath(normalized)}").use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT COUNT(*) FROM pending_updates WHERE applied = 1")
                assertTrue(rs.next())
                assertEquals(2, rs.getInt(1))
            }
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

    private fun copySourceIndexDatabase(
        originalRoot: Path,
        restoredRoot: Path,
    ) {
        val sourcePath = sourceIndexDatabasePath(originalRoot)
        DriverManager.getConnection("jdbc:sqlite:$sourcePath").use { conn ->
            conn.createStatement().use { stmt -> stmt.execute("PRAGMA wal_checkpoint(FULL)") }
        }
        val restoredPath = sourceIndexDatabasePath(restoredRoot)
        Files.createDirectories(restoredPath.parent)
        Files.list(sourcePath.parent).use { files ->
            files
                .filter { it.fileName.toString().startsWith(sourcePath.fileName.toString()) }
                .forEach { file ->
                    Files.copy(file, restoredPath.parent.resolve(file.fileName), StandardCopyOption.REPLACE_EXISTING)
                }
        }
    }

    private fun assertSchemaUsesInternedPaths(dbPath: Path) {
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.prepareStatement("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'path_prefixes'")
                .use { stmt ->
                    val rs = stmt.executeQuery()
                    assertTrue(rs.next())
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

    private fun assertTableColumns(
        conn: java.sql.Connection,
        tableName: String,
        present: Set<String>,
        absent: Set<String>,
    ) {
        conn.prepareStatement("PRAGMA table_info($tableName)").use { stmt ->
            val rs = stmt.executeQuery()
            val columns = buildSet {
                while (rs.next()) add(rs.getString("name"))
            }
            present.forEach { column -> assertTrue(column in columns) }
            absent.forEach { column -> assertFalse(column in columns) }
        }
    }
}
