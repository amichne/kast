package io.github.amichne.kast.standalone

import io.github.amichne.kast.indexstore.FileIndexUpdate
import io.github.amichne.kast.indexstore.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.kastCacheDirectory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SqliteCacheInvariantTest {
    @TempDir
    lateinit var workspaceRoot: Path

    // ── 1. DB location ──────────────────────────────────────────────────

    @Test
    fun `SQLite database is created under gradle kast cache directory`() {
        val normalized = normalizeStandalonePath(workspaceRoot)
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
        }
        val expected = normalized.resolve(".gradle/kast/cache/source-index.db")
        assertTrue(Files.isRegularFile(expected)) {
            "Expected DB at $expected but it was not found"
        }
    }

    // ── 2. Schema version mismatch ──────────────────────────────────────

    @Test
    fun `schema version mismatch triggers full rebuild`() {
        val normalized = normalizeStandalonePath(workspaceRoot)
        val cacheDir = kastCacheDirectory(normalized)
        Files.createDirectories(cacheDir)
        val dbPath = cacheDir.resolve("source-index.db")

        // Seed the DB with a future schema version (with generation column)
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE schema_version (version INTEGER NOT NULL, generation INTEGER NOT NULL DEFAULT 0)")
                stmt.execute("INSERT INTO schema_version (version, generation) VALUES (999, 0)")
            }
        }

        SqliteSourceIndexStore(normalized).use { store ->
            val schemaValid = store.ensureSchema()
            assertFalse(schemaValid) {
                "ensureSchema() should return false (cache miss) on version mismatch"
            }
        }

        // After rebuild the version should be the current one.
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.prepareStatement("SELECT version FROM schema_version LIMIT 1").use { stmt ->
                val rs = stmt.executeQuery()
                assertTrue(rs.next())
                assertEquals(4, rs.getInt(1))
            }
        }
    }

    // ── 3. Identifier-to-path round-trip ────────────────────────────────

    @Test
    fun `identifier to path mappings round-trip correctly`() {
        val normalized = normalizeStandalonePath(workspaceRoot)
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()

            val updates = (1..50).map { i ->
                FileIndexUpdate(
                    path = "/src/file$i.kt",
                    identifiers = setOf("Ident_${i}_a", "Ident_${i}_b"),
                    packageName = "pkg$i",
                    moduleName = "mod$i",
                    imports = setOf("import.a$i"),
                    wildcardImports = setOf("wild$i"),
                )
            }
            store.saveFullIndex(updates, manifest = emptyMap())

            val index = MutableSourceIdentifierIndex.fromSourceIndexSnapshot(store.loadSourceIndexSnapshot())
            for (update in updates) {
                for (id in update.identifiers) {
                    val paths = index.candidatePathsFor(id)
                    assertTrue(paths.contains(update.path)) {
                        "Expected identifier '$id' to map to '${update.path}', got $paths"
                    }
                }
            }
        }
    }

    // ── 4. Bidirectional symbol references (pending) ────────────────────

    @Test
    fun `bidirectional symbol reference links`() {
        val normalized = normalizeStandalonePath(workspaceRoot)
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
        }
        val dbPath = kastCacheDirectory(normalized).resolve("source-index.db")

        SqliteSourceIndexStore(normalized).use { store ->
            store.upsertSymbolReference("/src/Caller.kt", 42, "com.example.Foo", "/src/Foo.kt", 10)
            store.upsertSymbolReference("/src/Caller.kt", 99, "com.example.Bar", "/src/Bar.kt", 5)
            store.upsertSymbolReference("/src/Other.kt", 7, "com.example.Foo", "/src/Foo.kt", 10)

            val sources = store.referencesToSymbol("com.example.Foo").map { it.sourcePath }
            assertEquals(2, sources.size)
            assertTrue(sources.containsAll(listOf("/src/Caller.kt", "/src/Other.kt")))

            val targets = store.referencesFromFile("/src/Caller.kt").map { it.targetFqName }
            assertEquals(2, targets.size)
            assertTrue(targets.containsAll(listOf("com.example.Foo", "com.example.Bar")))
        }
    }

    // ── 5. Concurrent reads during writes (WAL) ─────────────────────────

    @Test
    fun `concurrent reads during writes do not block WAL mode`() {
        val normalized = normalizeStandalonePath(workspaceRoot)
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                listOf(
                    FileIndexUpdate(
                        path = "/src/Seed.kt",
                        identifiers = setOf("Seed"),
                        packageName = "seed",
                        moduleName = null,
                        imports = emptySet(),
                        wildcardImports = emptySet(),
                    ),
                ),
                manifest = mapOf("/src/Seed.kt" to 1L),
            )
        }

        val dbUrl = "jdbc:sqlite:${kastCacheDirectory(normalized).resolve("source-index.db")}"
        val writerReady = CountDownLatch(1)
        val readerDone = CountDownLatch(1)
        val readerSucceeded = AtomicBoolean(false)

        // Writer: hold an open write transaction
        val writerThread = Thread {
            DriverManager.getConnection(dbUrl).use { conn ->
                conn.createStatement().use { s -> s.execute("PRAGMA journal_mode=WAL") }
                conn.autoCommit = false
                conn.prepareStatement("INSERT OR IGNORE INTO path_prefixes (dir_path) VALUES (?)").use { stmt ->
                    stmt.setString(1, "writer")
                    stmt.executeUpdate()
                }
                val prefixId = conn.prepareStatement("SELECT prefix_id FROM path_prefixes WHERE dir_path = ?")
                    .use { stmt ->
                        stmt.setString(1, "writer")
                        val rs = stmt.executeQuery()
                        assertTrue(rs.next())
                        rs.getInt(1)
                    }
                conn.prepareStatement(
                    "INSERT OR IGNORE INTO identifier_paths (identifier, prefix_id, filename) VALUES (?, ?, ?)",
                ).use { stmt ->
                    stmt.setString(1, "WriterIdent")
                    stmt.setInt(2, prefixId)
                    stmt.setString(3, "Writer.kt")
                    stmt.executeUpdate()
                }
                // Signal that the write txn is open
                writerReady.countDown()
                // Wait for the reader to finish before committing
                readerDone.await(5, TimeUnit.SECONDS)
                conn.commit()
            }
        }

        // Reader: read while the writer holds its transaction
        val readerThread = Thread {
            writerReady.await(5, TimeUnit.SECONDS)
            DriverManager.getConnection(dbUrl).use { conn ->
                conn.createStatement().use { s -> s.execute("PRAGMA journal_mode=WAL") }
                conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery("SELECT COUNT(*) FROM identifier_paths")
                    rs.next()
                    // Should see at least the seed row
                    assertTrue(rs.getInt(1) >= 1)
                    readerSucceeded.set(true)
                }
            }
            readerDone.countDown()
        }

        writerThread.start()
        readerThread.start()

        // Both threads must complete within 2 seconds
        writerThread.join(2_000)
        readerThread.join(2_000)

        assertTrue(readerSucceeded.get()) {
            "Reader should have completed without blocking"
        }
    }

    // ── 6. Integrity after simulated crash ──────────────────────────────

    @Test
    fun `database integrity after simulated crash`() {
        val normalized = normalizeStandalonePath(workspaceRoot)
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
        }

        val dbUrl = "jdbc:sqlite:${kastCacheDirectory(normalized).resolve("source-index.db")}"

        // Begin a transaction, insert data, then close WITHOUT committing
        val conn = DriverManager.getConnection(dbUrl)
        conn.autoCommit = false
        conn.prepareStatement("INSERT OR IGNORE INTO path_prefixes (dir_path) VALUES (?)").use { stmt ->
            stmt.setString(1, "crash")
            stmt.executeUpdate()
        }
        val prefixId = conn.prepareStatement("SELECT prefix_id FROM path_prefixes WHERE dir_path = ?").use { stmt ->
            stmt.setString(1, "crash")
            val rs = stmt.executeQuery()
            assertTrue(rs.next())
            rs.getInt(1)
        }
        conn.prepareStatement(
            "INSERT OR IGNORE INTO identifier_paths (identifier, prefix_id, filename) VALUES (?, ?, ?)",
        ).use { stmt ->
            stmt.setString(1, "Orphan")
            stmt.setInt(2, prefixId)
            stmt.setString(3, "Orphan.kt")
            stmt.executeUpdate()
        }
        // Simulate crash — close the connection without commit
        conn.close()

        // Re-open and verify integrity
        DriverManager.getConnection(dbUrl).use { freshConn ->
            freshConn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("PRAGMA integrity_check")
                assertTrue(rs.next())
                assertEquals("ok", rs.getString(1))
            }

            // The uncommitted row should NOT be present
            freshConn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM identifier_paths WHERE identifier = 'Orphan'",
                )
                rs.next()
                assertEquals(0, rs.getInt(1)) {
                    "Uncommitted data should be rolled back after crash"
                }
            }
        }
    }

    // ── 7. removeFile cascades ──────────────────────────────────────────

    @Test
    fun `removing a file cascades to identifiers and metadata`() {
        val normalized = normalizeStandalonePath(workspaceRoot)
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()

            val targetPath = "/src/Target.kt"
            val otherPath = "/src/Other.kt"

            store.saveFullIndex(
                listOf(
                    FileIndexUpdate(
                        path = targetPath,
                        identifiers = setOf("Alpha", "Beta"),
                        packageName = "target",
                        moduleName = "modTarget",
                        imports = setOf("import.x"),
                        wildcardImports = emptySet(),
                    ),
                    FileIndexUpdate(
                        path = otherPath,
                        identifiers = setOf("Gamma"),
                        packageName = "other",
                        moduleName = "modOther",
                        imports = emptySet(),
                        wildcardImports = emptySet(),
                    ),
                ),
                manifest = mapOf(targetPath to 100L, otherPath to 200L),
            )

            store.removeFile(targetPath)
        }

        // Verify via raw JDBC that all target rows are gone
        val dbUrl = "jdbc:sqlite:${kastCacheDirectory(normalized).resolve("source-index.db")}"
        DriverManager.getConnection(dbUrl).use { conn ->
            fun countRowsForPath(table: String): Int {
                conn.prepareStatement("SELECT COUNT(*) FROM $table WHERE filename = ?").use { stmt ->
                    stmt.setString(1, "Target.kt")
                    val rs = stmt.executeQuery()
                    rs.next()
                    return rs.getInt(1)
                }
            }

            assertEquals(0, countRowsForPath("identifier_paths")) {
                "identifier_paths rows should be removed"
            }
            assertEquals(0, countRowsForPath("file_metadata")) {
                "file_metadata row should be removed"
            }
            assertEquals(0, countRowsForPath("file_manifest")) {
                "file_manifest row should be removed"
            }

            // Other file should still be intact
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM identifier_paths WHERE filename = 'Other.kt'",
                )
                rs.next()
                assertTrue(rs.getInt(1) > 0) { "Other file's identifiers should remain" }
            }
        }
    }

    // ── 8. upsertSymbolReference round-trips through referencesToSymbol ─

    @Test
    fun `upsertSymbolReference round-trips through referencesToSymbol`() {
        val normalized = normalizeStandalonePath(workspaceRoot)
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()

            store.upsertSymbolReference(
                sourcePath = "/src/Caller.kt",
                sourceOffset = 42,
                targetFqName = "com.example.Foo",
                targetPath = "/src/Foo.kt",
                targetOffset = 10,
            )
            store.upsertSymbolReference(
                sourcePath = "/src/Caller.kt",
                sourceOffset = 99,
                targetFqName = "com.example.Bar",
                targetPath = "/src/Bar.kt",
                targetOffset = 5,
            )
            store.upsertSymbolReference(
                sourcePath = "/src/Other.kt",
                sourceOffset = 7,
                targetFqName = "com.example.Foo",
                targetPath = "/src/Foo.kt",
                targetOffset = 10,
            )

            val fooRefs = store.referencesToSymbol("com.example.Foo")
            assertEquals(2, fooRefs.size)
            assertTrue(fooRefs.map { it.sourcePath }.containsAll(listOf("/src/Caller.kt", "/src/Other.kt")))

            val barRefs = store.referencesToSymbol("com.example.Bar")
            assertEquals(1, barRefs.size)
            assertEquals("/src/Caller.kt", barRefs.single().sourcePath)
        }
    }

    // ── 9. referencesFromFile returns all outgoing references ────────────

    @Test
    fun `referencesFromFile returns all outgoing references`() {
        val normalized = normalizeStandalonePath(workspaceRoot)
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()

            store.upsertSymbolReference(
                sourcePath = "/src/Caller.kt",
                sourceOffset = 42,
                targetFqName = "com.example.Foo",
                targetPath = "/src/Foo.kt",
                targetOffset = 10,
            )
            store.upsertSymbolReference(
                sourcePath = "/src/Caller.kt",
                sourceOffset = 99,
                targetFqName = "com.example.Bar",
                targetPath = "/src/Bar.kt",
                targetOffset = 5,
            )
            store.upsertSymbolReference(
                sourcePath = "/src/Other.kt",
                sourceOffset = 7,
                targetFqName = "com.example.Foo",
                targetPath = "/src/Foo.kt",
                targetOffset = 10,
            )

            val callerRefs = store.referencesFromFile("/src/Caller.kt")
            assertEquals(2, callerRefs.size)
            assertTrue(callerRefs.map { it.targetFqName }.containsAll(listOf("com.example.Foo", "com.example.Bar")))
        }
    }

    // ── 10. clearReferencesFromFile removes only that file's references ──

    @Test
    fun `clearReferencesFromFile removes only that file references`() {
        val normalized = normalizeStandalonePath(workspaceRoot)
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()

            store.upsertSymbolReference(
                sourcePath = "/src/Caller.kt",
                sourceOffset = 42,
                targetFqName = "com.example.Foo",
                targetPath = "/src/Foo.kt",
                targetOffset = 10,
            )
            store.upsertSymbolReference(
                sourcePath = "/src/Other.kt",
                sourceOffset = 7,
                targetFqName = "com.example.Foo",
                targetPath = "/src/Foo.kt",
                targetOffset = 10,
            )

            store.clearReferencesFromFile("/src/Caller.kt")

            val callerRefs = store.referencesFromFile("/src/Caller.kt")
            assertTrue(callerRefs.isEmpty())

            val otherRefs = store.referencesFromFile("/src/Other.kt")
            assertEquals(1, otherRefs.size)
            assertEquals("com.example.Foo", otherRefs.single().targetFqName)
        }
    }

    // ── 11. upsert replaces existing reference at same source location ───

    @Test
    fun `upsert replaces existing reference at same source location`() {
        val normalized = normalizeStandalonePath(workspaceRoot)
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()

            store.upsertSymbolReference(
                sourcePath = "/src/Caller.kt",
                sourceOffset = 42,
                targetFqName = "com.example.Target",
                targetPath = "/src/Old.kt",
                targetOffset = 10,
            )

            store.upsertSymbolReference(
                sourcePath = "/src/Caller.kt",
                sourceOffset = 42,
                targetFqName = "com.example.Target",
                targetPath = "/src/New.kt",
                targetOffset = 20,
            )

            val refs = store.referencesToSymbol("com.example.Target")
            assertEquals(1, refs.size, "Upsert should replace, not duplicate")
            assertEquals("/src/New.kt", refs.single().targetPath)
            assertEquals(20, refs.single().targetOffset)
        }
    }

    // ── 12. referencesToSymbol returns empty list for unknown symbol ─────

    @Test
    fun `referencesToSymbol returns empty list for unknown symbol`() {
        val normalized = normalizeStandalonePath(workspaceRoot)
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            val result = store.referencesToSymbol("does.not.Exist")
            assertTrue(result.isEmpty())
        }
    }

    // ── 13. Generation counter ──────────────────────────────────────────

    @Test
    fun `generation counter tracks index rebuilds`() {
        val normalized = normalizeStandalonePath(workspaceRoot)

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            val gen1 = store.readGeneration()

            val gen2 = store.incrementGeneration()
            assertTrue(gen2 > gen1) {
                "Generation should increment (was $gen1, now $gen2)"
            }

            val gen3 = store.incrementGeneration()
            assertTrue(gen3 > gen2) {
                "Generation should increment again (was $gen2, now $gen3)"
            }
        }
    }

    // ── Schema uplift: stale v2 database missing symbol_references ───────

    @Test
    fun `ensureSchema creates missing symbol_references in stale v2 database`() {
        val normalized = normalizeStandalonePath(workspaceRoot)
        val cacheDir = kastCacheDirectory(normalized)
        Files.createDirectories(cacheDir)
        val dbPath = cacheDir.resolve("source-index.db")

        // Seed a v2 DB that pre-dates symbol_references (the table is absent).
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "CREATE TABLE schema_version (version INTEGER NOT NULL, generation INTEGER NOT NULL DEFAULT 0)",
                )
                stmt.execute("INSERT INTO schema_version (version, generation) VALUES (3, 0)")
                stmt.execute(
                    "CREATE TABLE identifier_paths (identifier TEXT NOT NULL, path TEXT NOT NULL, PRIMARY KEY (identifier, path))",
                )
                stmt.execute(
                    "CREATE TABLE file_metadata (path TEXT PRIMARY KEY, package_name TEXT, module_name TEXT, imports TEXT, wildcard_imports TEXT)",
                )
                stmt.execute(
                    "CREATE TABLE file_manifest (path TEXT PRIMARY KEY, last_modified_millis INTEGER NOT NULL)",
                )
                // Intentionally NO symbol_references table — this is the stale-DB scenario
            }
        }

        SqliteSourceIndexStore(normalized).use { store ->
            val wasValid = store.ensureSchema()
            assertFalse(wasValid) { "ensureSchema() should rebuild old schema versions" }

            // Additive uplift: symbol_references must be queryable after ensureSchema()
            val refs = store.referencesToSymbol("io.example.Foo")
            assertTrue(refs.isEmpty()) { "Freshly uplifted DB should have no references" }

            // Phase-2 writes must also succeed without throwing
            store.upsertSymbolReference(
                sourcePath = "/src/Use.kt",
                sourceOffset = 42,
                targetFqName = "io.example.Foo",
                targetPath = "/src/Foo.kt",
                targetOffset = 0,
            )
            val afterInsert = store.referencesToSymbol("io.example.Foo")
            assertEquals(1, afterInsert.size) { "Inserted reference should be queryable" }
        }
    }

    @Test
    fun `referencesToSymbol returns empty gracefully when DB is deleted at runtime`() {
        val normalized = normalizeStandalonePath(workspaceRoot)
        val cacheDir = kastCacheDirectory(normalized)
        val dbPath = cacheDir.resolve("source-index.db")

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.upsertSymbolReference(
                sourcePath = "/src/Use.kt",
                sourceOffset = 10,
                targetFqName = "io.example.Bar",
                targetPath = "/src/Bar.kt",
                targetOffset = 0,
            )

            // Simulate CacheManager.invalidateAll() deleting the cache directory
            Files.deleteIfExists(dbPath)

            // Must not throw — the store detects the deleted file and reconnects
            val refs = store.referencesToSymbol("io.example.Bar")
            assertTrue(refs.isEmpty()) { "No references expected after DB was deleted" }
        }
    }
}
