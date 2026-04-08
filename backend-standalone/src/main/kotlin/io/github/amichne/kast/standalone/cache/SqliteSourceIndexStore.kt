package io.github.amichne.kast.standalone.cache

import io.github.amichne.kast.standalone.MutableSourceIdentifierIndex
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

private const val SCHEMA_VERSION = 1

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
 * SQLite-backed store for the source identifier index and file manifest.
 *
 * All data for both the identifier index and the file manifest live in a single
 * `source-index.db` database under the kast cache directory. WAL journal mode
 * is enabled so readers never block writers.
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
                // the next getConnection() creates a fresh file.
                runCatching { conn.close() }
                cachedConnection = null
            }
            Files.createDirectories(dbPath.parent)
            val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode=WAL")
                stmt.execute("PRAGMA synchronous=NORMAL")
                stmt.execute("PRAGMA busy_timeout=5000")
            }
            cachedConnection = conn
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
     * @return `true` if the existing schema was valid, `false` if tables were
     *   dropped and recreated (caller should treat this as a cache miss).
     */
    fun ensureSchema(): Boolean {
        val conn = connection()
        val version = readSchemaVersion(conn)
        if (version == SCHEMA_VERSION) return true
        conn.autoCommit = false
        try {
            dropAllTables(conn)
            createAllTables(conn)
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        }
        return false
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
            stmt.execute("DROP TABLE IF EXISTS schema_version")
            stmt.execute("DROP TABLE IF EXISTS identifier_paths")
            stmt.execute("DROP TABLE IF EXISTS file_metadata")
            stmt.execute("DROP TABLE IF EXISTS file_manifest")
        }
    }

    private fun createAllTables(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)")
            stmt.execute("INSERT INTO schema_version (version) VALUES ($SCHEMA_VERSION)")
            stmt.execute(
                """CREATE TABLE IF NOT EXISTS identifier_paths (
                    identifier TEXT NOT NULL,
                    path TEXT NOT NULL,
                    PRIMARY KEY (identifier, path)
                )""",
            )
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_identifier_paths_path ON identifier_paths(path)",
            )
            stmt.execute(
                """CREATE TABLE IF NOT EXISTS file_metadata (
                    path TEXT PRIMARY KEY,
                    package_name TEXT,
                    module_name TEXT,
                    imports TEXT,
                    wildcard_imports TEXT
                )""",
            )
            stmt.execute(
                """CREATE TABLE IF NOT EXISTS file_manifest (
                    path TEXT PRIMARY KEY,
                    last_modified_millis INTEGER NOT NULL
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

    /** Removes all index/manifest rows for the given path. */
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
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        }
    }

    /** Reads all rows and constructs a [MutableSourceIdentifierIndex]. */
    fun loadFullIndex(): MutableSourceIdentifierIndex {
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
