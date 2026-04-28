package io.github.amichne.kast.indexstore

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

internal const val SOURCE_INDEX_SCHEMA_VERSION = 4

/**
 * SQLite-backed store for the source identifier index, file manifest,
 * symbol references, and workspace discovery cache.
 *
 * All data lives in a single `source-index.db` database under the kast cache
 * directory. WAL journal mode is enabled so readers never block writers.
 */
class SqliteSourceIndexStore(workspaceRoot: Path) : AutoCloseable, SourceIndexWriter {
    private val workspaceRoot: Path = workspaceRoot
    private val dbPath: Path = sourceIndexDatabasePath(workspaceRoot)
    private val pathCodec = PathInterningCodec(workspaceRoot)
    private val fqCodec = StringInterningCodec(
        tableName = "fq_names",
        idColumn = "fq_id",
        valueColumn = "fq_name",
    )
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
                    throw e
                } finally {
                    conn.autoCommit = true
                }
                loadInterningTables(conn)
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
                loadInterningTables(conn)
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
            loadInterningTables(conn)
            return false
        }
    }

    private fun additiveMigration(conn: Connection) {
        conn.createStatement().use { stmt ->
            if (!columnExists(conn, tableName = "schema_version", columnName = "head_commit")) {
                stmt.execute("ALTER TABLE schema_version ADD COLUMN head_commit TEXT")
            }
            stmt.execute(
                """CREATE TABLE IF NOT EXISTS workspace_discovery (
                    cache_key TEXT PRIMARY KEY,
                    schema_version INTEGER NOT NULL,
                    payload TEXT NOT NULL
                )""",
            )
        }
        if (!sourceIndexTablesAreCompatible(conn)) {
            rebuildDerivedIndexTables(conn)
        } else {
            createSourceIndexIndexes(conn)
        }
    }

    private fun sourceIndexTablesAreCompatible(conn: Connection): Boolean =
        tableExists(conn, "path_prefixes") &&
        tableExists(conn, "fq_names") &&
        tableExists(conn, "identifier_paths") &&
        tableExists(conn, "file_metadata") &&
        tableExists(conn, "file_imports") &&
        tableExists(conn, "file_wildcard_imports") &&
        tableExists(conn, "file_manifest") &&
        tableExists(conn, "symbol_references") &&
        tableExists(conn, "pending_updates") &&
        columnExists(conn, "path_prefixes", "prefix_id") &&
        columnExists(conn, "path_prefixes", "dir_path") &&
        columnExists(conn, "fq_names", "fq_id") &&
        columnExists(conn, "fq_names", "fq_name") &&
        columnExists(conn, "identifier_paths", "prefix_id") &&
        columnExists(conn, "identifier_paths", "filename") &&
        !columnExists(conn, "identifier_paths", "path") &&
        columnExists(conn, "file_metadata", "prefix_id") &&
        columnExists(conn, "file_metadata", "filename") &&
        columnExists(conn, "file_metadata", "package_fq_id") &&
        !columnExists(conn, "file_metadata", "package_name") &&
        !columnExists(conn, "file_metadata", "imports") &&
        !columnExists(conn, "file_metadata", "wildcard_imports") &&
        !columnExists(conn, "file_metadata", "path") &&
        columnExists(conn, "file_manifest", "prefix_id") &&
        columnExists(conn, "file_manifest", "filename") &&
        !columnExists(conn, "file_manifest", "path") &&
        columnExists(conn, "symbol_references", "src_prefix_id") &&
        columnExists(conn, "symbol_references", "src_filename") &&
        columnExists(conn, "symbol_references", "target_fq_id") &&
        columnExists(conn, "symbol_references", "tgt_prefix_id") &&
        columnExists(conn, "symbol_references", "tgt_filename") &&
        !columnExists(conn, "symbol_references", "target_fq_name") &&
        !columnExists(conn, "symbol_references", "source_path") &&
        !columnExists(conn, "symbol_references", "target_path")

    private fun tableExists(
        conn: Connection,
        tableName: String,
    ): Boolean =
        conn.prepareStatement(
            """SELECT 1 FROM sqlite_master
               WHERE type = 'table' AND name = ?
               LIMIT 1""",
        ).use { stmt ->
            stmt.setString(1, tableName)
            stmt.executeQuery().let { rs -> rs.next() }
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
            stmt.execute("DROP TABLE IF EXISTS pending_updates")
            stmt.execute("DROP TABLE IF EXISTS symbol_references")
            stmt.execute("DROP TABLE IF EXISTS file_wildcard_imports")
            stmt.execute("DROP TABLE IF EXISTS file_imports")
            stmt.execute("DROP TABLE IF EXISTS identifier_paths")
            stmt.execute("DROP TABLE IF EXISTS file_metadata")
            stmt.execute("DROP TABLE IF EXISTS file_manifest")
            stmt.execute("DROP TABLE IF EXISTS fq_names")
            stmt.execute("DROP TABLE IF EXISTS path_prefixes")
            stmt.execute("DROP TABLE IF EXISTS schema_version")
            // workspace_discovery is intentionally preserved across source index schema upgrades —
            // its data is independent of path interning and other source index schema changes.
        }
    }

    private fun rebuildDerivedIndexTables(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute("DROP TABLE IF EXISTS pending_updates")
            stmt.execute("DROP TABLE IF EXISTS symbol_references")
            stmt.execute("DROP TABLE IF EXISTS file_wildcard_imports")
            stmt.execute("DROP TABLE IF EXISTS file_imports")
            stmt.execute("DROP TABLE IF EXISTS identifier_paths")
            stmt.execute("DROP TABLE IF EXISTS file_metadata")
            stmt.execute("DROP TABLE IF EXISTS file_manifest")
            stmt.execute("DROP TABLE IF EXISTS fq_names")
            stmt.execute("DROP TABLE IF EXISTS path_prefixes")
            createPathPrefixTable(stmt)
            createFqNameTable(stmt)
            createSourceIndexTables(stmt)
            createSourceIndexIndexes(stmt)
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

            createPathPrefixTable(stmt)
            createFqNameTable(stmt)
            createSourceIndexTables(stmt)
            createSourceIndexIndexes(stmt)

            stmt.execute(
                """CREATE TABLE IF NOT EXISTS workspace_discovery (
                    cache_key TEXT PRIMARY KEY,
                    schema_version INTEGER NOT NULL,
                    payload TEXT NOT NULL
                )""",
            )
        }
    }

    private fun createPathPrefixTable(stmt: java.sql.Statement) {
        stmt.execute(
            """CREATE TABLE IF NOT EXISTS path_prefixes (
                prefix_id INTEGER PRIMARY KEY,
                dir_path TEXT NOT NULL UNIQUE
            )""",
        )
    }

    private fun createFqNameTable(stmt: java.sql.Statement) {
        stmt.execute(
            """CREATE TABLE IF NOT EXISTS fq_names (
                fq_id INTEGER PRIMARY KEY,
                fq_name TEXT NOT NULL UNIQUE
            )""",
        )
    }

    private fun createSourceIndexTables(stmt: java.sql.Statement) {
        stmt.execute(
            """CREATE TABLE IF NOT EXISTS identifier_paths (
                identifier TEXT NOT NULL,
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                PRIMARY KEY (identifier, prefix_id, filename)
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS file_metadata (
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                package_fq_id INTEGER,
                module_name TEXT,
                PRIMARY KEY (prefix_id, filename)
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS file_imports (
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                fq_id INTEGER NOT NULL,
                PRIMARY KEY (prefix_id, filename, fq_id)
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS file_wildcard_imports (
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                fq_id INTEGER NOT NULL,
                PRIMARY KEY (prefix_id, filename, fq_id)
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS file_manifest (
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                last_modified_millis INTEGER NOT NULL,
                PRIMARY KEY (prefix_id, filename)
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS symbol_references (
                src_prefix_id INTEGER NOT NULL,
                src_filename TEXT NOT NULL,
                source_offset INTEGER NOT NULL,
                target_fq_id INTEGER NOT NULL,
                tgt_prefix_id INTEGER,
                tgt_filename TEXT,
                target_offset INTEGER,
                PRIMARY KEY (src_prefix_id, src_filename, source_offset, target_fq_id)
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS pending_updates (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                op TEXT NOT NULL CHECK(op IN ('upsert_file','remove_file','upsert_ref','remove_ref')),
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                payload TEXT,
                session_id TEXT,
                epoch_ms INTEGER NOT NULL,
                applied INTEGER NOT NULL DEFAULT 0
            )""",
        )
    }

    private fun createSourceIndexIndexes(conn: Connection) {
        conn.createStatement().use { stmt -> createSourceIndexIndexes(stmt) }
    }

    private fun createSourceIndexIndexes(stmt: java.sql.Statement) {
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_ip_prefix_file ON identifier_paths(prefix_id, filename)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_metadata_module ON file_metadata(module_name)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_metadata_package ON file_metadata(package_fq_id)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_imports_fq ON file_imports(fq_id)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_wildcard_imports_fq ON file_wildcard_imports(fq_id)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_symref_target ON symbol_references(target_fq_id)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_symref_source ON symbol_references(src_prefix_id, src_filename)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_symref_target_file ON symbol_references(tgt_prefix_id, tgt_filename)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_pending_updates_unapplied ON pending_updates(applied, seq)")
    }

    fun saveFullIndex(
        updates: List<FileIndexUpdate>,
        manifest: Map<String, Long>,
    ) {
        synchronized(writeLock) {
            val conn = connection()
            conn.autoCommit = false
            try {
                internPathsInTransaction(conn, updates.map { it.path } + manifest.keys)
                internFqNamesInTransaction(conn, updates.flatMapTo(mutableSetOf()) { update ->
                    buildList {
                        update.packageName?.let(::add)
                        addAll(update.imports)
                        addAll(update.wildcardImports)
                    }
                })
                conn.createStatement().use { stmt ->
                    stmt.execute("DELETE FROM file_wildcard_imports")
                    stmt.execute("DELETE FROM file_imports")
                    stmt.execute("DELETE FROM identifier_paths")
                    stmt.execute("DELETE FROM file_metadata")
                    stmt.execute("DELETE FROM file_manifest")
                }
                for (update in updates) {
                    insertFileDataInTransaction(conn, update)
                }
                insertManifestInTransaction(conn, manifest)
                pruneReferencesOutsideManifestInTransaction(conn, manifest.keys)
                conn.createStatement().use { stmt -> stmt.execute("DELETE FROM pending_updates") }
                conn.commit()
            } catch (e: Exception) {
                rollbackAndReloadPrefixes(conn)
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
                internPathsInTransaction(conn, listOf(update.path))
                internFqNamesInTransaction(conn, fqNamesFor(update))
                insertFileDataInTransaction(conn, update)
                conn.commit()
            } catch (e: Exception) {
                rollbackAndReloadPrefixes(conn)
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun removeFile(path: String) {
        synchronized(writeLock) {
            val conn = connection()
            loadInterningTables(conn)
            val encodedPath = pathCodec.encodeIfInterned(path) ?: return
            conn.autoCommit = false
            try {
                deleteFileRowsInTransaction(conn, encodedPath.first, encodedPath.second)
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
            loadInterningTables(conn)
            val candidatePathsByIdentifier = mutableMapOf<String, MutableList<String>>()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT identifier, prefix_id, filename FROM identifier_paths")
                while (rs.next()) {
                    candidatePathsByIdentifier
                        .getOrPut(rs.getString(1)) { mutableListOf() }
                        .add(pathCodec.decode(rs.getInt(2), rs.getString(3)))
                }
            }

            val moduleNameByPath = mutableMapOf<String, String>()
            val packageByPath = mutableMapOf<String, String>()
            val importsByPath = mutableMapOf<String, List<String>>()
            val wildcardImportPackagesByPath = mutableMapOf<String, List<String>>()

            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(
                    "SELECT prefix_id, filename, package_fq_id, module_name FROM file_metadata",
                )
                while (rs.next()) {
                    val path = pathCodec.decode(rs.getInt(1), rs.getString(2))
                    rs.getNullableInt(3)?.let { packageByPath[path] = fqCodec.resolve(it) }
                    rs.getString(4)?.let { moduleNameByPath[path] = it }
                }
            }

            loadFileFqNames(conn, "file_imports", importsByPath)
            loadFileFqNames(conn, "file_wildcard_imports", wildcardImportPackagesByPath)

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
                internPathsInTransaction(conn, entries.keys)
                conn.createStatement().use { stmt -> stmt.execute("DELETE FROM file_manifest") }
                insertManifestInTransaction(conn, entries)
                conn.commit()
            } catch (e: Exception) {
                rollbackAndReloadPrefixes(conn)
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
            val conn = connection()
            conn.autoCommit = false
            try {
                internPathsInTransaction(conn, listOf(path))
                val (prefixId, filename) = pathCodec.encode(path)
                conn.prepareStatement(
                    """INSERT OR REPLACE INTO file_manifest (prefix_id, filename, last_modified_millis)
                       VALUES (?, ?, ?)""",
                ).use { stmt ->
                    stmt.setInt(1, prefixId)
                    stmt.setString(2, filename)
                    stmt.setLong(3, lastModifiedMillis)
                    stmt.executeUpdate()
                }
                conn.commit()
            } catch (e: Exception) {
                rollbackAndReloadPrefixes(conn)
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun loadManifest(): Map<String, Long>? {
        if (!dbExists()) return null
        return synchronized(writeLock) {
            try {
                val conn = connection()
                loadInterningTables(conn)
                buildMap {
                    conn.createStatement().use { stmt ->
                        val rs = stmt.executeQuery("SELECT prefix_id, filename, last_modified_millis FROM file_manifest")
                        while (rs.next()) put(pathCodec.decode(rs.getInt(1), rs.getString(2)), rs.getLong(3))
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
            val conn = connection()
            conn.autoCommit = false
            try {
                internPathsInTransaction(conn, listOfNotNull(sourcePath, targetPath))
                internFqNamesInTransaction(conn, setOf(targetFqName))
                upsertSymbolReferenceInTransaction(
                    conn = conn,
                    sourcePath = sourcePath,
                    sourceOffset = sourceOffset,
                    targetFqName = targetFqName,
                    targetPath = targetPath,
                    targetOffset = targetOffset,
                )
                conn.commit()
            } catch (e: Exception) {
                rollbackAndReloadPrefixes(conn)
                throw e
            } finally {
                conn.autoCommit = true
            }
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
        val (sourcePrefixId, sourceFilename) = pathCodec.encode(sourcePath)
        val targetPathParts = targetPath?.let { pathCodec.encode(it) }
        val targetFqId = fqCodec.getOrCreate(conn, targetFqName)
        conn.prepareStatement(
            """INSERT OR REPLACE INTO symbol_references
               (src_prefix_id, src_filename, source_offset, target_fq_id, tgt_prefix_id, tgt_filename, target_offset)
               VALUES (?, ?, ?, ?, ?, ?, ?)""",
        ).use { stmt ->
            stmt.setInt(1, sourcePrefixId)
            stmt.setString(2, sourceFilename)
            stmt.setInt(3, sourceOffset)
            stmt.setInt(4, targetFqId)
            if (targetPathParts != null) {
                stmt.setInt(5, targetPathParts.first)
                stmt.setString(6, targetPathParts.second)
            } else {
                stmt.setNull(5, java.sql.Types.INTEGER)
                stmt.setNull(6, java.sql.Types.VARCHAR)
            }
            if (targetOffset != null) stmt.setInt(7, targetOffset) else stmt.setNull(7, java.sql.Types.INTEGER)
            stmt.executeUpdate()
        }
    }

    fun referencesToSymbol(targetFqName: String): List<SymbolReferenceRow> {
        synchronized(writeLock) {
            val conn = connection()
            loadInterningTables(conn)
            val targetFqId = fqCodec.idFor(targetFqName) ?: return emptyList()
            return conn.prepareStatement(
                """SELECT src_prefix_id, src_filename, source_offset, target_fq_id,
                          tgt_prefix_id, tgt_filename, target_offset
                   FROM symbol_references
                   WHERE target_fq_id = ?""",
            ).use { stmt ->
                stmt.setInt(1, targetFqId)
                val rs = stmt.executeQuery()
                buildList {
                    while (rs.next()) {
                        val rowTargetFqId = rs.getInt(4)
                        add(
                            SymbolReferenceRow(
                                sourcePath = pathCodec.decode(rs.getInt(1), rs.getString(2)),
                                sourceOffset = rs.getInt(3),
                                targetFqName = fqCodec.resolve(rowTargetFqId),
                                targetPath = decodeNullablePath(rs, prefixColumn = 5, filenameColumn = 6),
                                targetOffset = rs.getNullableInt(7),
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
            loadInterningTables(conn)
            val (prefixId, filename) = pathCodec.encodeIfInterned(sourcePath) ?: return emptyList()
            return conn.prepareStatement(
                """SELECT src_prefix_id, src_filename, source_offset, target_fq_id,
                          tgt_prefix_id, tgt_filename, target_offset
                   FROM symbol_references
                   WHERE src_prefix_id = ? AND src_filename = ?""",
            ).use { stmt ->
                stmt.setInt(1, prefixId)
                stmt.setString(2, filename)
                val rs = stmt.executeQuery()
                buildList {
                    while (rs.next()) {
                        val rowTargetFqId = rs.getInt(4)
                        add(
                            SymbolReferenceRow(
                                sourcePath = pathCodec.decode(rs.getInt(1), rs.getString(2)),
                                sourceOffset = rs.getInt(3),
                                targetFqName = fqCodec.resolve(rowTargetFqId),
                                targetPath = decodeNullablePath(rs, prefixColumn = 5, filenameColumn = 6),
                                targetOffset = rs.getNullableInt(7),
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

    private fun clearReferencesFromFileInTransaction(
        conn: Connection,
        sourcePath: String,
    ) {
        loadInterningTables(conn)
        val (prefixId, filename) = pathCodec.encodeIfInterned(sourcePath) ?: return
        conn.prepareStatement("DELETE FROM symbol_references WHERE src_prefix_id = ? AND src_filename = ?")
            .use { stmt ->
                stmt.setInt(1, prefixId)
                stmt.setString(2, filename)
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
            loadInterningTables(conn)
            val encodedSources = sourcePaths.mapNotNull { pathCodec.encodeIfInterned(it) }.toSet()
            if (encodedSources.isEmpty()) {
                conn.createStatement().use { stmt -> stmt.execute("DELETE FROM symbol_references") }
                return
            }
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """CREATE TEMP TABLE IF NOT EXISTS temp_valid_sources (
                        prefix_id INTEGER NOT NULL,
                        filename TEXT NOT NULL,
                        PRIMARY KEY (prefix_id, filename)
                    )""",
                )
                stmt.execute("DELETE FROM temp_valid_sources")
            }
            try {
                conn.prepareStatement("INSERT OR IGNORE INTO temp_valid_sources (prefix_id, filename) VALUES (?, ?)")
                    .use { stmt ->
                        for ((prefixId, filename) in encodedSources) {
                            stmt.setInt(1, prefixId)
                            stmt.setString(2, filename)
                            stmt.addBatch()
                        }
                        stmt.executeBatch()
                    }
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """DELETE FROM symbol_references
                           WHERE NOT EXISTS (
                               SELECT 1
                               FROM temp_valid_sources valid
                               WHERE valid.prefix_id = symbol_references.src_prefix_id
                                 AND valid.filename = symbol_references.src_filename
                           )""",
                    )
                }
            } finally {
                conn.createStatement().use { stmt -> stmt.execute("DROP TABLE IF EXISTS temp_valid_sources") }
            }
        }
    }

    fun replaceReferencesFromFiles(referencesBySource: List<Pair<String, List<SymbolReferenceRow>>>) {
        synchronized(writeLock) {
            val conn = connection()
            conn.autoCommit = false
            try {
                val pathsToIntern = referencesBySource.flatMap { (filePath, refs) ->
                    buildList {
                        add(filePath)
                        refs.forEach { ref ->
                            add(ref.sourcePath)
                            ref.targetPath?.let(::add)
                        }
                    }
                }
                internPathsInTransaction(conn, pathsToIntern)
                internFqNamesInTransaction(conn, referencesBySource.flatMapTo(mutableSetOf()) { (_, refs) ->
                    refs.map { it.targetFqName }
                })
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
                rollbackAndReloadPrefixes(conn)
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun appendPendingUpdate(
        op: String,
        path: String,
        payload: String?,
        sessionId: String? = null,
    ) {
        synchronized(writeLock) {
            val conn = connection()
            val (prefixId, filename) = pathCodec.encodeOrCreate(conn, path)
            conn.prepareStatement(
                """INSERT INTO pending_updates (op, prefix_id, filename, payload, session_id, epoch_ms)
                   VALUES (?, ?, ?, ?, ?, ?)""",
            ).use { stmt ->
                stmt.setString(1, op)
                stmt.setInt(2, prefixId)
                stmt.setString(3, filename)
                stmt.setString(4, payload)
                stmt.setString(5, sessionId)
                stmt.setLong(6, System.currentTimeMillis())
                stmt.executeUpdate()
            }
        }
    }

    fun reconcilePendingUpdates(): Int {
        synchronized(writeLock) {
            val conn = connection()
            loadInterningTables(conn)
            conn.autoCommit = false
            return try {
                val pending = readLatestPendingUpdates(conn)
                for (update in pending) {
                    applyPendingUpdate(conn, update)
                }
                markPendingUpdatesApplied(conn, pending)
                cleanupAppliedPendingUpdates(conn)
                conn.commit()
                pending.size
            } catch (e: Exception) {
                rollbackAndReloadPrefixes(conn)
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
        val (prefixId, filename) = pathCodec.encode(update.path)
        conn.prepareStatement("DELETE FROM identifier_paths WHERE prefix_id = ? AND filename = ?").use { stmt ->
            stmt.setInt(1, prefixId)
            stmt.setString(2, filename)
            stmt.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM file_metadata WHERE prefix_id = ? AND filename = ?").use { stmt ->
            stmt.setInt(1, prefixId)
            stmt.setString(2, filename)
            stmt.executeUpdate()
        }
        for (table in listOf("file_imports", "file_wildcard_imports")) {
            conn.prepareStatement("DELETE FROM $table WHERE prefix_id = ? AND filename = ?").use { stmt ->
                stmt.setInt(1, prefixId)
                stmt.setString(2, filename)
                stmt.executeUpdate()
            }
        }
        if (update.identifiers.isNotEmpty()) {
            conn.prepareStatement("INSERT OR IGNORE INTO identifier_paths (identifier, prefix_id, filename) VALUES (?, ?, ?)")
                .use { stmt ->
                for (identifier in update.identifiers) {
                    stmt.setString(1, identifier)
                    stmt.setInt(2, prefixId)
                    stmt.setString(3, filename)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
        update.packageName?.let { fqCodec.getOrCreate(conn, it) }
        fqCodec.batchEnsure(conn, update.imports + update.wildcardImports)
        conn.prepareStatement(
            """INSERT OR REPLACE INTO file_metadata
               (prefix_id, filename, package_fq_id, module_name)
               VALUES (?, ?, ?, ?)""",
        ).use { stmt ->
            stmt.setInt(1, prefixId)
            stmt.setString(2, filename)
            update.packageName
                ?.let(fqCodec::idFor)
                ?.let { stmt.setInt(3, it) }
            ?: stmt.setNull(3, java.sql.Types.INTEGER)
            stmt.setString(4, update.moduleName)
            stmt.executeUpdate()
        }
        insertFileFqNamesInTransaction(conn, tableName = "file_imports", prefixId, filename, update.imports)
        insertFileFqNamesInTransaction(
            conn,
            tableName = "file_wildcard_imports",
            prefixId,
            filename,
            update.wildcardImports
        )
    }

    private fun insertManifestInTransaction(
        conn: Connection,
        entries: Map<String, Long>,
    ) {
        if (entries.isEmpty()) return
        conn.prepareStatement("INSERT INTO file_manifest (prefix_id, filename, last_modified_millis) VALUES (?, ?, ?)")
            .use { stmt ->
            entries.forEach { (path, millis) ->
                val (prefixId, filename) = pathCodec.encode(path)
                stmt.setInt(1, prefixId)
                stmt.setString(2, filename)
                stmt.setLong(3, millis)
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
                       SELECT 1
                       FROM file_manifest manifest
                       WHERE manifest.prefix_id = symbol_references.src_prefix_id
                         AND manifest.filename = symbol_references.src_filename
                   )
                      OR (
                          tgt_prefix_id IS NOT NULL
                          AND NOT EXISTS (
                              SELECT 1
                              FROM file_manifest manifest
                              WHERE manifest.prefix_id = symbol_references.tgt_prefix_id
                                AND manifest.filename = symbol_references.tgt_filename
                          )
                      )""",
            )
        }
    }

    private fun internPathsInTransaction(
        conn: Connection,
        paths: Iterable<String>,
    ) {
        val dirs = paths.map { pathCodec.decompose(it).first }.toSet()
        pathCodec.batchIntern(conn, dirs)
    }

    private fun internFqNamesInTransaction(
        conn: Connection,
        fqNames: Set<String>,
    ) {
        fqCodec.batchEnsure(conn, fqNames)
    }

    private fun fqNamesFor(update: FileIndexUpdate): Set<String> = buildSet {
        update.packageName?.let(::add)
        addAll(update.imports)
        addAll(update.wildcardImports)
    }

    private fun loadInterningTables(conn: Connection) {
        pathCodec.loadPrefixes(conn)
        fqCodec.loadAll(conn)
    }

    private fun rollbackAndReloadPrefixes(conn: Connection) {
        conn.rollback()
        runCatching { loadInterningTables(conn) }
    }

    private fun loadFileFqNames(
        conn: Connection,
        tableName: String,
        target: MutableMap<String, List<String>>,
    ) {
        val byPath = mutableMapOf<String, MutableList<String>>()
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT prefix_id, filename, fq_id FROM $tableName")
            while (rs.next()) {
                val path = pathCodec.decode(rs.getInt(1), rs.getString(2))
                val fqName = fqCodec.resolve(rs.getInt(3))
                byPath.getOrPut(path) { mutableListOf() }.add(fqName)
            }
        }
        byPath.forEach { (path, fqNames) ->
            target[path] = fqNames.sorted()
        }
    }

    private fun insertFileFqNamesInTransaction(
        conn: Connection,
        tableName: String,
        prefixId: Int,
        filename: String,
        fqNames: Set<String>,
    ) {
        if (fqNames.isEmpty()) return
        fqCodec.batchEnsure(conn, fqNames)
        conn.prepareStatement("INSERT OR IGNORE INTO $tableName (prefix_id, filename, fq_id) VALUES (?, ?, ?)")
            .use { stmt ->
                fqNames.sorted().forEach { fqName ->
                    stmt.setInt(1, prefixId)
                    stmt.setString(2, filename)
                    stmt.setInt(3, checkNotNull(fqCodec.idFor(fqName)) { "FQ name was not interned: $fqName" })
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
    }

    private fun deleteFileRowsInTransaction(
        conn: Connection,
        prefixId: Int,
        filename: String,
    ) {
        for (table in listOf(
            "identifier_paths",
            "file_metadata",
            "file_imports",
            "file_wildcard_imports",
            "file_manifest"
        )) {
            conn.prepareStatement("DELETE FROM $table WHERE prefix_id = ? AND filename = ?").use { stmt ->
                stmt.setInt(1, prefixId)
                stmt.setString(2, filename)
                stmt.executeUpdate()
            }
        }
        conn.prepareStatement(
            """DELETE FROM symbol_references
               WHERE (src_prefix_id = ? AND src_filename = ?)
                  OR (tgt_prefix_id = ? AND tgt_filename = ?)""",
        ).use { stmt ->
            stmt.setInt(1, prefixId)
            stmt.setString(2, filename)
            stmt.setInt(3, prefixId)
            stmt.setString(4, filename)
            stmt.executeUpdate()
        }
    }

    private fun readLatestPendingUpdates(conn: Connection): List<PendingUpdateRow> =
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery(
                """SELECT p.seq, p.op, p.prefix_id, p.filename, p.payload
                   FROM pending_updates p
                   INNER JOIN (
                       SELECT prefix_id, filename, MAX(seq) AS max_seq
                       FROM pending_updates
                       WHERE applied = 0
                       GROUP BY prefix_id, filename
                   ) latest ON p.seq = latest.max_seq
                   ORDER BY p.seq""",
            )
            buildList {
                while (rs.next()) {
                    add(
                        PendingUpdateRow(
                            seq = rs.getLong(1),
                            op = rs.getString(2),
                            prefixId = rs.getInt(3),
                            filename = rs.getString(4),
                            payload = rs.getString(5),
                        ),
                    )
                }
            }
        }

    private fun applyPendingUpdate(
        conn: Connection,
        update: PendingUpdateRow,
    ) {
        val path = pathCodec.decode(update.prefixId, update.filename)
        when (update.op) {
            "upsert_file" -> {
                val payload = defaultCacheJson.decodeFromString(
                    PendingFilePayload.serializer(),
                    requireNotNull(update.payload)
                )
                val fileUpdate = FileIndexUpdate(
                    path = path,
                    identifiers = payload.identifiers.toSet(),
                    packageName = payload.packageName,
                    moduleName = payload.moduleName,
                    imports = payload.imports.toSet(),
                    wildcardImports = payload.wildcardImports.toSet(),
                )
                internFqNamesInTransaction(conn, fqNamesFor(fileUpdate))
                insertFileDataInTransaction(conn, fileUpdate)
            }

            "remove_file" -> deleteFileRowsInTransaction(conn, update.prefixId, update.filename)
            "upsert_ref" -> {
                val payload = defaultCacheJson.decodeFromString(
                    PendingReferencePayload.serializer(),
                    requireNotNull(update.payload)
                )
                val targetPath = payload.targetPath?.let(::normalizePendingPayloadPath)
                internPathsInTransaction(conn, listOfNotNull(path, targetPath))
                internFqNamesInTransaction(conn, setOf(payload.targetFqName))
                upsertSymbolReferenceInTransaction(
                    conn = conn,
                    sourcePath = path,
                    sourceOffset = payload.sourceOffset,
                    targetFqName = payload.targetFqName,
                    targetPath = targetPath,
                    targetOffset = payload.targetOffset,
                )
            }

            "remove_ref" -> {
                val payload = defaultCacheJson.decodeFromString(
                    PendingRemoveReferencePayload.serializer(),
                    requireNotNull(update.payload)
                )
                removeSymbolReferenceInTransaction(
                    conn = conn,
                    sourcePrefixId = update.prefixId,
                    sourceFilename = update.filename,
                    sourceOffset = payload.sourceOffset,
                    targetFqName = payload.targetFqName,
                )
            }

            else -> error("Unsupported pending update operation: ${update.op}")
        }
    }

    private fun removeSymbolReferenceInTransaction(
        conn: Connection,
        sourcePrefixId: Int,
        sourceFilename: String,
        sourceOffset: Int,
        targetFqName: String,
    ) {
        val targetFqId = fqCodec.idFor(targetFqName) ?: return
        conn.prepareStatement(
            """DELETE FROM symbol_references
               WHERE src_prefix_id = ?
                 AND src_filename = ?
                 AND source_offset = ?
                 AND target_fq_id = ?""",
        ).use { stmt ->
            stmt.setInt(1, sourcePrefixId)
            stmt.setString(2, sourceFilename)
            stmt.setInt(3, sourceOffset)
            stmt.setInt(4, targetFqId)
            stmt.executeUpdate()
        }
    }

    private fun normalizePendingPayloadPath(path: String): String {
        val rawPath = Path.of(path)
        return if (rawPath.isAbsolute) {
            rawPath.normalize().toString()
        } else {
            workspaceRoot.resolve(rawPath).normalize().toString()
        }
    }

    private fun markPendingUpdatesApplied(
        conn: Connection,
        updates: List<PendingUpdateRow>,
    ) {
        if (updates.isEmpty()) return
        conn.prepareStatement(
            """UPDATE pending_updates
               SET applied = 1
               WHERE applied = 0 AND prefix_id = ? AND filename = ?""",
        ).use { stmt ->
            updates.forEach { update ->
                stmt.setInt(1, update.prefixId)
                stmt.setString(2, update.filename)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    private fun cleanupAppliedPendingUpdates(conn: Connection) {
        val retentionStartMs = System.currentTimeMillis() - PENDING_UPDATE_RETENTION_MS
        conn.prepareStatement("DELETE FROM pending_updates WHERE applied = 1 AND epoch_ms < ?").use { stmt ->
            stmt.setLong(1, retentionStartMs)
            stmt.executeUpdate()
        }
    }

    private fun decodeNullablePath(
        rs: java.sql.ResultSet,
        prefixColumn: Int,
        filenameColumn: Int,
    ): String? {
        val prefixId = rs.getNullableInt(prefixColumn) ?: return null
        val filename = requireNotNull(rs.getString(filenameColumn)) {
            "Path filename is missing for prefix_id=$prefixId"
        }
        return pathCodec.decode(prefixId, filename)
    }

    private fun java.sql.ResultSet.getNullableInt(column: Int): Int? =
        getObject(column)?.let { (it as Number).toInt() }

    private data class PendingUpdateRow(
        val seq: Long,
        val op: String,
        val prefixId: Int,
        val filename: String,
        val payload: String?,
    )

    @Serializable
    private data class PendingFilePayload(
        val identifiers: List<String> = emptyList(),
        val packageName: String? = null,
        val moduleName: String? = null,
        val imports: List<String> = emptyList(),
        val wildcardImports: List<String> = emptyList(),
    )

    @Serializable
    private data class PendingReferencePayload(
        val sourceOffset: Int,
        val targetFqName: String,
        val targetPath: String? = null,
        val targetOffset: Int? = null,
    )

    @Serializable
    private data class PendingRemoveReferencePayload(
        val sourceOffset: Int,
        val targetFqName: String,
    )

    private companion object {
        const val PENDING_UPDATE_RETENTION_MS = 7L * 24 * 60 * 60 * 1_000
    }
}
