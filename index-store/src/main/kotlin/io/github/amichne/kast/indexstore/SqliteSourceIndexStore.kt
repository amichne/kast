package io.github.amichne.kast.indexstore

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

internal const val SOURCE_INDEX_SCHEMA_VERSION = 3

/**
 * SQLite-backed store for the source identifier index, file manifest,
 * symbol references, and workspace discovery cache.
 *
 * All data lives in a single `source-index.db` database under the kast cache
 * directory. WAL journal mode is enabled so readers never block writers.
 */
class SqliteSourceIndexStore(workspaceRoot: Path) : AutoCloseable, SourceIndexWriter {
    private val dbPath: Path = sourceIndexDatabasePath(workspaceRoot)
    private val connectionLock = Any()
    private val writeLock = Any()

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
     * @return `true` if the existing schema was valid, `false` if tables were
     * dropped and recreated.
     */
    fun ensureSchema(): Boolean {
        synchronized(writeLock) {
            val conn = connection()
            val version = readSchemaVersion(conn)
            if (version == SOURCE_INDEX_SCHEMA_VERSION) {
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
    }

    private fun additiveMigration(conn: Connection) {
        conn.createStatement().use { stmt ->
            if (!columnExists(conn, tableName = "schema_version", columnName = "head_commit")) {
                stmt.execute("ALTER TABLE schema_version ADD COLUMN head_commit TEXT")
            }
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

    private fun columnExists(
        conn: Connection,
        tableName: String,
        columnName: String,
    ): Boolean =
        conn.prepareStatement("PRAGMA table_info($tableName)").use { stmt ->
            val rs = stmt.executeQuery()
            while (rs.next()) {
                if (rs.getString("name") == columnName) return true
            }
            false
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
                    generation INTEGER NOT NULL DEFAULT 0,
                    head_commit TEXT
                )""",
            )
            stmt.execute("INSERT INTO schema_version (version, generation, head_commit) VALUES ($SOURCE_INDEX_SCHEMA_VERSION, 0, NULL)")

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

    fun saveFullIndex(
        updates: List<FileIndexUpdate>,
        manifest: Map<String, Long>,
    ) {
        synchronized(writeLock) {
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
                pruneReferencesOutsideManifestInTransaction(conn, manifest.keys)
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun saveFileIndex(update: FileIndexUpdate) {
        synchronized(writeLock) {
            val conn = connection()
            conn.autoCommit = false
            try {
                insertFileDataInTransaction(conn, update)
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun removeFile(path: String) {
        synchronized(writeLock) {
            val conn = connection()
            conn.autoCommit = false
            try {
                for (table in listOf("identifier_paths", "file_metadata", "file_manifest")) {
                    conn.prepareStatement("DELETE FROM $table WHERE path = ?").use { stmt ->
                        stmt.setString(1, path)
                        stmt.executeUpdate()
                    }
                }
                conn.prepareStatement("DELETE FROM symbol_references WHERE source_path = ? OR target_path = ?").use { stmt ->
                    stmt.setString(1, path)
                    stmt.setString(2, path)
                    stmt.executeUpdate()
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun loadSourceIndexSnapshot(): SourceIndexSnapshot {
        synchronized(writeLock) {
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

            return SourceIndexSnapshot(
                candidatePathsByIdentifier = candidatePathsByIdentifier,
                moduleNameByPath = moduleNameByPath,
                packageByPath = packageByPath,
                importsByPath = importsByPath,
                wildcardImportPackagesByPath = wildcardImportPackagesByPath,
            )
        }
    }

    fun saveManifest(entries: Map<String, Long>) {
        synchronized(writeLock) {
            val conn = connection()
            conn.autoCommit = false
            try {
                conn.createStatement().use { stmt -> stmt.execute("DELETE FROM file_manifest") }
                insertManifestInTransaction(conn, entries)
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun updateManifestEntry(
        path: String,
        lastModifiedMillis: Long,
    ) {
        synchronized(writeLock) {
            connection().prepareStatement(
                """INSERT OR REPLACE INTO file_manifest (path, last_modified_millis)
                   VALUES (?, ?)""",
            ).use { stmt ->
                stmt.setString(1, path)
                stmt.setLong(2, lastModifiedMillis)
                stmt.executeUpdate()
            }
        }
    }

    fun loadManifest(): Map<String, Long>? {
        if (!dbExists()) return null
        return synchronized(writeLock) {
            try {
                val conn = connection()
                buildMap {
                    conn.createStatement().use { stmt ->
                        val rs = stmt.executeQuery("SELECT path, last_modified_millis FROM file_manifest")
                        while (rs.next()) put(rs.getString(1), rs.getLong(2))
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    fun upsertSymbolReference(
        sourcePath: String,
        sourceOffset: Int,
        targetFqName: String,
        targetPath: String?,
        targetOffset: Int?,
    ) {
        synchronized(writeLock) {
            upsertSymbolReferenceInTransaction(
                conn = connection(),
                sourcePath = sourcePath,
                sourceOffset = sourceOffset,
                targetFqName = targetFqName,
                targetPath = targetPath,
                targetOffset = targetOffset,
            )
        }
    }

    private fun upsertSymbolReferenceInTransaction(
        conn: Connection,
        sourcePath: String,
        sourceOffset: Int,
        targetFqName: String,
        targetPath: String?,
        targetOffset: Int?,
    ) {
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

    fun referencesToSymbol(targetFqName: String): List<SymbolReferenceRow> {
        synchronized(writeLock) {
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
    }

    fun referencesFromFile(sourcePath: String): List<SymbolReferenceRow> {
        synchronized(writeLock) {
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
    }

    fun clearReferencesFromFile(sourcePath: String) {
        synchronized(writeLock) {
            clearReferencesFromFileInTransaction(connection(), sourcePath)
        }
    }

    private fun clearReferencesFromFileInTransaction(conn: Connection, sourcePath: String) {
        conn.prepareStatement("DELETE FROM symbol_references WHERE source_path = ?").use { stmt ->
            stmt.setString(1, sourcePath)
            stmt.executeUpdate()
        }
    }

    fun removeReferencesOutsideSources(sourcePaths: Collection<String>) {
        synchronized(writeLock) {
            val conn = connection()
            if (sourcePaths.isEmpty()) {
                conn.createStatement().use { stmt -> stmt.execute("DELETE FROM symbol_references") }
                return
            }
            val placeholders = sourcePaths.joinToString(",") { "?" }
            conn.prepareStatement("DELETE FROM symbol_references WHERE source_path NOT IN ($placeholders)").use { stmt ->
                sourcePaths.forEachIndexed { index, sourcePath -> stmt.setString(index + 1, sourcePath) }
                stmt.executeUpdate()
            }
        }
    }

    fun replaceReferencesFromFiles(referencesBySource: List<Pair<String, List<SymbolReferenceRow>>>) {
        synchronized(writeLock) {
            val conn = connection()
            conn.autoCommit = false
            try {
                for ((filePath, refs) in referencesBySource) {
                    clearReferencesFromFileInTransaction(conn, filePath)
                    refs.forEach { ref ->
                        upsertSymbolReferenceInTransaction(
                            conn = conn,
                            sourcePath = ref.sourcePath,
                            sourceOffset = ref.sourceOffset,
                            targetFqName = ref.targetFqName,
                            targetPath = ref.targetPath,
                            targetOffset = ref.targetOffset,
                        )
                    }
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun readWorkspaceDiscovery(cacheKey: String): String? {
        synchronized(writeLock) {
            val conn = connection()
            return conn.prepareStatement(
                "SELECT payload FROM workspace_discovery WHERE cache_key = ?",
            ).use { stmt ->
                stmt.setString(1, cacheKey)
                val rs = stmt.executeQuery()
                if (rs.next()) rs.getString(1) else null
            }
        }
    }

    fun writeWorkspaceDiscovery(cacheKey: String, schemaVersion: Int, payload: String) {
        synchronized(writeLock) {
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
    }

    fun readGeneration(): Int {
        synchronized(writeLock) {
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
    }

    fun incrementGeneration(): Int {
        synchronized(writeLock) {
            val conn = connection()
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("UPDATE schema_version SET generation = generation + 1")
            }
            return readGeneration()
        }
    }

    fun readHeadCommit(): String? {
        synchronized(writeLock) {
            val conn = connection()
            return try {
                conn.prepareStatement("SELECT head_commit FROM schema_version LIMIT 1").use { stmt ->
                    val rs = stmt.executeQuery()
                    if (rs.next()) rs.getString(1) else null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    fun writeHeadCommit(sha: String) {
        synchronized(writeLock) {
            connection().prepareStatement("UPDATE schema_version SET head_commit = ?").use { stmt ->
                stmt.setString(1, sha)
                stmt.executeUpdate()
            }
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
            conn.prepareStatement("INSERT OR IGNORE INTO identifier_paths (identifier, path) VALUES (?, ?)").use { stmt ->
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
        conn.prepareStatement("INSERT INTO file_manifest (path, last_modified_millis) VALUES (?, ?)").use { stmt ->
            entries.forEach { (path, millis) ->
                stmt.setString(1, path)
                stmt.setLong(2, millis)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    private fun pruneReferencesOutsideManifestInTransaction(
        conn: Connection,
        manifestPaths: Set<String>,
    ) {
        if (manifestPaths.isEmpty()) {
            conn.createStatement().use { stmt -> stmt.execute("DELETE FROM symbol_references") }
            return
        }
        conn.createStatement().use { stmt ->
            stmt.execute(
                """DELETE FROM symbol_references
                   WHERE NOT EXISTS (
                       SELECT 1 FROM file_manifest manifest WHERE manifest.path = symbol_references.source_path
                   )
                      OR (
                          target_path IS NOT NULL
                          AND NOT EXISTS (
                              SELECT 1 FROM file_manifest manifest WHERE manifest.path = symbol_references.target_path
                          )
                      )""",
            )
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
