package io.github.amichne.kast.standalone.cache

import io.github.amichne.kast.standalone.MutableSourceIdentifierIndex
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

private const val SCHEMA_VERSION = 3

/**
 * Represents all index data for a single file — used to batch-write to SQLite.
 */
internal data class FileIndexUpdate(
    val path: String,
    val identifiers: Set<String>,
    val packageName: String?,
    val moduleName: String?,
    val imports: Set<String>,
    val wildcardImports: Set<String>,
)

/**
 * A row from the `symbol_references` table — a reference from a source
 * location to a target symbol identified by its fully-qualified name.
 */
internal data class SymbolReferenceRow(
    val sourcePath: String,
    val sourceOffset: Int,
    val targetFqName: String,
    val targetPath: String?,
    val targetOffset: Int?,
)

/**
 * SQLite-backed store for the source identifier index, file manifest,
 * symbol references, workspace discovery cache, and call hierarchy cache.
 *
 * All data lives in a single `source-index.db` database under the kast
 * cache directory. WAL journal mode is enabled so readers never block writers.
 */
internal class SqliteSourceIndexStore(workspaceRoot: Path) : AutoCloseable {
    private val dbPath: Path = kastCacheDirectory(workspaceRoot).resolve("source-index.db")
    private val connectionLock = Any()

    @Volatile
    private var cachedConnection: Connection? = null

    fun dbExists(): Boolean = Files.isRegularFile(dbPath)

    private fun connection(): Connection {
        cachedConnection?.let { conn ->
            if (!conn.isClosed && Files.isRegularFile(dbPath)) return conn
        }
        synchronized(connectionLock) {
            cachedConnection?.let { conn ->
                if (!conn.isClosed && Files.isRegularFile(dbPath)) return conn
                // DB file was deleted (e.g. by CacheManager.invalidateAll()) while
                // the connection was still open. Close the orphaned connection so
                // the next call creates a fresh file.
                runCatching { conn.close() }
                cachedConnection = null
            }
            Files.createDirectories(dbPath.parent)
            val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode=WAL")
                stmt.execute("PRAGMA synchronous=NORMAL")
                stmt.execute("PRAGMA busy_timeout=5000")
                stmt.execute("PRAGMA cache_size=-64000")
                stmt.execute("PRAGMA mmap_size=268435456")
                stmt.execute("PRAGMA temp_store=MEMORY")
                stmt.execute("PRAGMA wal_autocheckpoint=1000")
                stmt.execute("PRAGMA foreign_keys=ON")
            }
            cachedConnection = conn
            // If this is a brand-new or fully empty database (no schema_version row),
            // bootstrap the full schema immediately so callers never hit a missing-table
            // error even without an explicit ensureSchema() call.
            if (readSchemaVersion(conn) == null) {
                conn.autoCommit = false
                try {
                    createAllTables(conn)
                    conn.commit()
                } catch (e: Exception) {
                    runCatching { conn.rollback() }
                } finally {
                    conn.autoCommit = true
                }
            }
            return conn
        }
    }

    override fun close() {
        synchronized(connectionLock) {
            cachedConnection?.let { conn ->
                runCatching { conn.close() }
                cachedConnection = null
            }
        }
    }

    /**
     * Ensures the database schema is present and at the current version.
     *
     * When the schema version matches, any tables that were added after the
     * version was set (e.g. `symbol_references` added to an existing v2 DB)
     * are created via `CREATE TABLE IF NOT EXISTS` — an additive, non-destructive
     * uplift that preserves all existing data.
     *
     * @return `true` if the existing schema was valid (and uplifted if needed),
     *   `false` if tables were dropped and recreated (caller should treat this
     *   as a cache miss).
     */
    fun ensureSchema(): Boolean {
        val conn = connection()
        val version = readSchemaVersion(conn)
        if (version == SCHEMA_VERSION) {
            // Additive uplift: create any tables that may be missing in older
            // databases that share the same schema_version number.
            additiveMigration(conn)
            return true
        }
        conn.autoCommit = false
        try {
            dropAllTables(conn)
            createAllTables(conn)
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
        return false
    }

    /**
     * Idempotently creates tables and indexes that may be absent in databases
     * whose `schema_version` predates the addition of those objects.
     * Safe to call on an already-fully-migrated database.
     */
    private fun additiveMigration(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute(
                """CREATE TABLE IF NOT EXISTS symbol_references (
                    source_path TEXT NOT NULL,
                    source_offset INTEGER NOT NULL,
                    target_fq_name TEXT NOT NULL,
                    target_path TEXT,
                    target_offset INTEGER,
                    PRIMARY KEY (source_path, source_offset, target_fq_name)
                )""",
            )
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_symref_target ON symbol_references(target_fq_name)",
            )
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_symref_source_path ON symbol_references(source_path)",
            )
            stmt.execute(
                """CREATE TABLE IF NOT EXISTS workspace_discovery (
                    cache_key TEXT PRIMARY KEY,
                    schema_version INTEGER NOT NULL,
                    payload TEXT NOT NULL
                )""",
            )
        }
    }

    private fun readSchemaVersion(conn: Connection): Int? = try {
        conn.prepareStatement("SELECT version FROM schema_version LIMIT 1").use { stmt ->
            stmt.executeQuery().let { rs -> if (rs.next()) rs.getInt(1) else null }
        }
    } catch (_: Exception) {
        null
    }

    private fun dropAllTables(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute("DROP TABLE IF EXISTS symbol_references")
            stmt.execute("DROP TABLE IF EXISTS identifier_paths")
            stmt.execute("DROP TABLE IF EXISTS file_metadata")
            stmt.execute("DROP TABLE IF EXISTS file_manifest")
            stmt.execute("DROP TABLE IF EXISTS workspace_discovery")
            stmt.execute("DROP TABLE IF EXISTS schema_version")
        }
    }

    private fun createAllTables(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute(
                """CREATE TABLE IF NOT EXISTS schema_version (
                    version INTEGER NOT NULL,
                    generation INTEGER NOT NULL DEFAULT 0
                )""",
            )
            stmt.execute("INSERT INTO schema_version (version, generation) VALUES ($SCHEMA_VERSION, 0)")

            stmt.execute(
                """CREATE TABLE IF NOT EXISTS identifier_paths (
                    identifier TEXT NOT NULL,
                    path TEXT NOT NULL,
                    PRIMARY KEY (identifier, path)
                )""",
            )
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_identifier_paths_path ON identifier_paths(path)")

            stmt.execute(
                """CREATE TABLE IF NOT EXISTS file_metadata (
                    path TEXT PRIMARY KEY,
                    package_name TEXT,
                    module_name TEXT,
                    imports TEXT,
                    wildcard_imports TEXT
                )""",
            )
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_metadata_module ON file_metadata(module_name)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_metadata_package ON file_metadata(package_name)")

            stmt.execute(
                """CREATE TABLE IF NOT EXISTS file_manifest (
                    path TEXT PRIMARY KEY,
                    last_modified_millis INTEGER NOT NULL
                )""",
            )

            stmt.execute(
                """CREATE TABLE IF NOT EXISTS symbol_references (
                    source_path TEXT NOT NULL,
                    source_offset INTEGER NOT NULL,
                    target_fq_name TEXT NOT NULL,
                    target_path TEXT,
                    target_offset INTEGER,
                    PRIMARY KEY (source_path, source_offset, target_fq_name)
                )""",
            )
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_symref_target ON symbol_references(target_fq_name)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_symref_source_path ON symbol_references(source_path)")

            stmt.execute(
                """CREATE TABLE IF NOT EXISTS workspace_discovery (
                    cache_key TEXT PRIMARY KEY,
                    schema_version INTEGER NOT NULL,
                    payload TEXT NOT NULL
                )""",
            )
        }
    }

    /**
     * Clears all existing index/manifest data and replaces it in a single
     * transaction — used for the full scheduled save and initial cold-start write.
     */
    fun saveFullIndex(
        updates: List<FileIndexUpdate>,
        manifest: Map<String, Long>,
    ) {
        val conn = connection()
        conn.autoCommit = false
        try {
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM identifier_paths")
                stmt.execute("DELETE FROM file_metadata")
                stmt.execute("DELETE FROM file_manifest")
            }
            for (update in updates) {
                insertFileDataInTransaction(conn, update)
            }
            insertManifestInTransaction(conn, manifest)
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        }
    }

    /** Upserts index data for a single file — used for incremental per-file refreshes. */
    fun saveFileIndex(update: FileIndexUpdate) {
        val conn = connection()
        conn.autoCommit = false
        try {
            insertFileDataInTransaction(conn, update)
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        }
    }

    /** Removes all index/manifest/reference rows for the given path. */
    fun removeFile(path: String) {
        val conn = connection()
        conn.autoCommit = false
        try {
            for (table in listOf("identifier_paths", "file_metadata", "file_manifest")) {
                conn.prepareStatement("DELETE FROM $table WHERE path = ?").use { stmt ->
                    stmt.setString(1, path)
                    stmt.executeUpdate()
                }
            }
            conn.prepareStatement("DELETE FROM symbol_references WHERE source_path = ?").use { stmt ->
                stmt.setString(1, path)
                stmt.executeUpdate()
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        }
    }

    /** Reads all rows and constructs a [MutableSourceIdentifierIndex]. */
    fun loadFullIndex(backingStore: SqliteSourceIndexStore? = null): MutableSourceIdentifierIndex {
        val conn = connection()
        val candidatePathsByIdentifier = mutableMapOf<String, MutableList<String>>()
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT identifier, path FROM identifier_paths")
            while (rs.next()) {
                candidatePathsByIdentifier
                    .getOrPut(rs.getString(1)) { mutableListOf() }
                    .add(rs.getString(2))
            }
        }

        val moduleNameByPath = mutableMapOf<String, String>()
        val packageByPath = mutableMapOf<String, String>()
        val importsByPath = mutableMapOf<String, List<String>>()
        val wildcardImportPackagesByPath = mutableMapOf<String, List<String>>()

        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery(
                "SELECT path, package_name, module_name, imports, wildcard_imports FROM file_metadata",
            )
            while (rs.next()) {
                val path = rs.getString(1)
                rs.getString(2)?.let { packageByPath[path] = it }
                rs.getString(3)?.let { moduleNameByPath[path] = it }
                rs.getString(4)?.decodeJsonArray()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { importsByPath[path] = it }
                rs.getString(5)?.decodeJsonArray()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { wildcardImportPackagesByPath[path] = it }
            }
        }

        return MutableSourceIdentifierIndex.fromCandidatePathsByIdentifier(
            candidatePathsByIdentifier = candidatePathsByIdentifier,
            moduleNameByPath = moduleNameByPath,
            packageByPath = packageByPath,
            importsByPath = importsByPath,
            wildcardImportPackagesByPath = wildcardImportPackagesByPath,
            backingStore = backingStore,
        )
    }

    /** Replaces the entire manifest table in a single transaction. */
    fun saveManifest(entries: Map<String, Long>) {
        val conn = connection()
        conn.autoCommit = false
        try {
            conn.createStatement().use { stmt -> stmt.execute("DELETE FROM file_manifest") }
            insertManifestInTransaction(conn, entries)
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        }
    }

    /** Returns the persisted manifest, or `null` if the database does not exist. */
    fun loadManifest(): Map<String, Long>? {
        if (!dbExists()) return null
        return try {
            val conn = connection()
            buildMap {
                conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery(
                        "SELECT path, last_modified_millis FROM file_manifest",
                    )
                    while (rs.next()) put(rs.getString(1), rs.getLong(2))
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    // ── Symbol references ────────────────────────────────────────────────

    /** Inserts or replaces a symbol reference from a source location to a target FQ name. */
    fun upsertSymbolReference(
        sourcePath: String,
        sourceOffset: Int,
        targetFqName: String,
        targetPath: String?,
        targetOffset: Int?,
    ) {
        val conn = connection()
        conn.prepareStatement(
            """INSERT OR REPLACE INTO symbol_references
               (source_path, source_offset, target_fq_name, target_path, target_offset)
               VALUES (?, ?, ?, ?, ?)""",
        ).use { stmt ->
            stmt.setString(1, sourcePath)
            stmt.setInt(2, sourceOffset)
            stmt.setString(3, targetFqName)
            stmt.setString(4, targetPath)
            if (targetOffset != null) stmt.setInt(5, targetOffset) else stmt.setNull(5, java.sql.Types.INTEGER)
            stmt.executeUpdate()
        }
    }

    /** Returns all references that target the given fully-qualified name. */
    fun referencesToSymbol(targetFqName: String): List<SymbolReferenceRow> {
        val conn = connection()
        return conn.prepareStatement(
            "SELECT source_path, source_offset, target_fq_name, target_path, target_offset FROM symbol_references WHERE target_fq_name = ?",
        ).use { stmt ->
            stmt.setString(1, targetFqName)
            val rs = stmt.executeQuery()
            buildList {
                while (rs.next()) {
                    add(
                        SymbolReferenceRow(
                            sourcePath = rs.getString(1),
                            sourceOffset = rs.getInt(2),
                            targetFqName = rs.getString(3),
                            targetPath = rs.getString(4),
                            targetOffset = rs.getObject(5) as? Int,
                        ),
                    )
                }
            }
        }
    }

    /** Returns all references originating from the given source file. */
    fun referencesFromFile(sourcePath: String): List<SymbolReferenceRow> {
        val conn = connection()
        return conn.prepareStatement(
            "SELECT source_path, source_offset, target_fq_name, target_path, target_offset FROM symbol_references WHERE source_path = ?",
        ).use { stmt ->
            stmt.setString(1, sourcePath)
            val rs = stmt.executeQuery()
            buildList {
                while (rs.next()) {
                    add(
                        SymbolReferenceRow(
                            sourcePath = rs.getString(1),
                            sourceOffset = rs.getInt(2),
                            targetFqName = rs.getString(3),
                            targetPath = rs.getString(4),
                            targetOffset = rs.getObject(5) as? Int,
                        ),
                    )
                }
            }
        }
    }

    /** Removes all symbol references originating from the given source file. */
    fun clearReferencesFromFile(sourcePath: String) {
        val conn = connection()
        conn.prepareStatement("DELETE FROM symbol_references WHERE source_path = ?").use { stmt ->
            stmt.setString(1, sourcePath)
            stmt.executeUpdate()
        }
    }

    // ── Workspace discovery cache ────────────────────────────────────────

    /** Returns the JSON payload for a workspace discovery cache entry, or `null` if absent. */
    fun readWorkspaceDiscovery(cacheKey: String): String? {
        val conn = connection()
        return conn.prepareStatement(
            "SELECT payload FROM workspace_discovery WHERE cache_key = ?",
        ).use { stmt ->
            stmt.setString(1, cacheKey)
            val rs = stmt.executeQuery()
            if (rs.next()) rs.getString(1) else null
        }
    }

    /** Writes or replaces a workspace discovery cache entry. */
    fun writeWorkspaceDiscovery(cacheKey: String, schemaVersion: Int, payload: String) {
        val conn = connection()
        conn.prepareStatement(
            "INSERT OR REPLACE INTO workspace_discovery (cache_key, schema_version, payload) VALUES (?, ?, ?)",
        ).use { stmt ->
            stmt.setString(1, cacheKey)
            stmt.setInt(2, schemaVersion)
            stmt.setString(3, payload)
            stmt.executeUpdate()
        }
    }

    // ── Transaction helpers ──────────────────────────────────────────────

    /** Begins a transaction for batch writes. */
    fun beginTransaction() {
        connection().autoCommit = false
    }

    /** Commits the current transaction. */
    fun commitTransaction() {
        val conn = connection()
        conn.commit()
        conn.autoCommit = true
    }

    /** Rolls back the current transaction. */
    fun rollbackTransaction() {
        val conn = connection()
        runCatching { conn.rollback() }
        conn.autoCommit = true
    }

    // ── Generation tracking ──────────────────────────────────────────────

    /** Reads the current generation counter from the schema_version table. */
    fun readGeneration(): Int {
        val conn = connection()
        return try {
            conn.prepareStatement("SELECT generation FROM schema_version LIMIT 1").use { stmt ->
                val rs = stmt.executeQuery()
                if (rs.next()) rs.getInt(1) else 0
            }
        } catch (_: Exception) {
            0
        }
    }

    /** Atomically increments and returns the new generation counter. */
    fun incrementGeneration(): Int {
        val conn = connection()
        conn.createStatement().use { stmt ->
            stmt.executeUpdate("UPDATE schema_version SET generation = generation + 1")
        }
        return readGeneration()
    }

    private fun insertFileDataInTransaction(
        conn: Connection,
        update: FileIndexUpdate,
    ) {
        conn.prepareStatement("DELETE FROM identifier_paths WHERE path = ?").use { stmt ->
            stmt.setString(1, update.path)
            stmt.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM file_metadata WHERE path = ?").use { stmt ->
            stmt.setString(1, update.path)
            stmt.executeUpdate()
        }
        if (update.identifiers.isNotEmpty()) {
            conn.prepareStatement(
                "INSERT OR IGNORE INTO identifier_paths (identifier, path) VALUES (?, ?)",
            ).use { stmt ->
                for (identifier in update.identifiers) {
                    stmt.setString(1, identifier)
                    stmt.setString(2, update.path)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
        conn.prepareStatement(
            """INSERT OR REPLACE INTO file_metadata
               (path, package_name, module_name, imports, wildcard_imports)
               VALUES (?, ?, ?, ?, ?)""",
        ).use { stmt ->
            stmt.setString(1, update.path)
            stmt.setString(2, update.packageName)
            stmt.setString(3, update.moduleName)
            stmt.setString(4, update.imports.sorted().encodeAsJsonArray())
            stmt.setString(5, update.wildcardImports.sorted().encodeAsJsonArray())
            stmt.executeUpdate()
        }
    }

    private fun insertManifestInTransaction(
        conn: Connection,
        entries: Map<String, Long>,
    ) {
        if (entries.isEmpty()) return
        conn.prepareStatement(
            "INSERT INTO file_manifest (path, last_modified_millis) VALUES (?, ?)",
        ).use { stmt ->
            entries.forEach { (path, millis) ->
                stmt.setString(1, path)
                stmt.setLong(2, millis)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    private fun List<String>.encodeAsJsonArray(): String =
        defaultCacheJson.encodeToString(ListSerializer(String.serializer()), this)

    private fun String.decodeJsonArray(): List<String>? = try {
        defaultCacheJson.decodeFromString(ListSerializer(String.serializer()), this)
    } catch (_: Exception) {
        null
    }
}
