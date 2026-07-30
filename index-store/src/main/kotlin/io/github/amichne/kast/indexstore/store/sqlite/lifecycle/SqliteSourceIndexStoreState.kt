package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.indexstore.snapshot.OverlayManifest
import io.github.amichne.kast.indexstore.store.codec.PathInterningCodec
import io.github.amichne.kast.indexstore.store.codec.StringInterningCodec
import io.github.amichne.kast.indexstore.store.jdbc.SqliteJdbcDriverBootstrap
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.concurrent.atomic.AtomicReference

internal class SqliteSourceIndexStoreState(
    workspaceIdentity: WorkspaceIdentity,
    internal val pageReadObserver: SourceIndexPageReadObserver,
) : AutoCloseable {
    internal val workspaceRoot: Path = workspaceIdentity.workspaceRootPath
    internal val dbPath: Path = workspaceIdentity.sourceIndexDatabaseFile
    private val overlayManifest: OverlayManifest? = dbPath.resolveSibling(REPOSITORY_OVERLAY_FILE)
        .takeIf(Files::isRegularFile)
        ?.let { path -> Json.decodeFromString(Files.readString(path)) }
    internal val repositoryBasePath: Path? = overlayManifest?.baseDatabase
        ?.let(Path::of)
        ?.toAbsolutePath()
        ?.normalize()
    internal val pathCodec = PathInterningCodec(workspaceRoot)
    internal val fqCodec = StringInterningCodec(
        tableName = "fq_names",
        idColumn = "fq_id",
        valueColumn = "fq_name",
    )
    internal val connectionLock = Any()
    internal val writeLock = Any()
    internal val schema: SqliteSourceIndexSchema by lazy { SqliteSourceIndexSchema(this) }

    @Volatile
    private var cachedConnection: Connection? = null

    @Volatile
    private var validatedSchemaConnection: Connection? = null

    @Volatile
    private var loadedInterningDataVersion: Long? = null

    private val committedManifestFileCount = AtomicReference(NonNegativeInt(0))

    internal fun dbExists(): Boolean = Files.isRegularFile(dbPath)

    internal fun prepareManifestFileCount() {
        if (!dbExists()) return
        synchronized(writeLock) {
            if (dbExists()) runCatching { connection() }
        }
    }

    internal fun committedManifestFileCount(): NonNegativeInt = committedManifestFileCount.get()

    internal fun connection(requireCurrentSchema: Boolean = true): Connection {
        cachedConnection?.let { conn ->
            if (!conn.isClosed && Files.isRegularFile(dbPath)) {
                if (!requireCurrentSchema || validatedSchemaConnection === conn) return conn
            }
        }
        synchronized(connectionLock) {
            cachedConnection?.let { conn ->
                if (!conn.isClosed && Files.isRegularFile(dbPath)) {
                    if (requireCurrentSchema && validatedSchemaConnection !== conn) {
                        schema.validateCurrentSchema(conn)
                        refreshManifestFileCount(conn)
                        validatedSchemaConnection = conn
                    }
                    return conn
                }
                // DB file was deleted (e.g. by CacheManager.invalidateAll()) while
                // the connection was still open. Close the orphaned connection so
                // the next call creates a fresh file.
                runCatching { conn.close() }
                cachedConnection = null
                validatedSchemaConnection = null
                loadedInterningDataVersion = null
                committedManifestFileCount.set(NonNegativeInt(0))
            }
            Files.createDirectories(dbPath.parent)
            SqliteJdbcDriverBootstrap.ensureRegistered()
            val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
            try {
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
                attachRepositoryBase(conn)
                if (schema.readSchemaVersion(conn) == null) {
                    conn.autoCommit = false
                    schema.createAllTables(conn)
                    conn.commit()
                    conn.autoCommit = true
                }
                if (requireCurrentSchema) {
                    schema.validateCurrentSchema(conn)
                    initializeRepositoryOverlay(conn)
                    reloadInterningTables(conn)
                    refreshManifestFileCount(conn)
                }
                cachedConnection = conn
                validatedSchemaConnection = conn.takeIf { requireCurrentSchema }
                return conn
            } catch (e: Exception) {
                if (!conn.autoCommit) runCatching { conn.rollback() }
                runCatching { conn.close() }
                throw e
            } finally {
                if (!conn.isClosed) conn.autoCommit = true
            }
        }
    }

    private fun attachRepositoryBase(conn: Connection) {
        val base = repositoryBasePath ?: return
        check(Files.isRegularFile(base)) { "Repository snapshot base is unavailable: $base" }
        val uri = "${base.toUri().toASCIIString()}?mode=ro&immutable=1".replace("'", "''")
        conn.createStatement().use { statement ->
            statement.execute("ATTACH DATABASE '$uri' AS repository_base")
            val rows = statement.executeQuery("SELECT version FROM repository_base.schema_version LIMIT 1")
            check(rows.next() && rows.getInt(1) == SOURCE_INDEX_SCHEMA_VERSION) {
                "Repository snapshot base schema does not match $SOURCE_INDEX_SCHEMA_VERSION"
            }
        }
    }

    internal fun initializeRepositoryOverlay(conn: Connection) {
        val manifest = overlayManifest ?: return
        conn.createStatement().use { statement ->
            statement.execute(
                """CREATE TABLE IF NOT EXISTS repository_overlay_state (
                    target_snapshot TEXT PRIMARY KEY
                ) WITHOUT ROWID""",
            )
        }
        val previousAutoCommit = conn.autoCommit
        conn.autoCommit = false
        try {
            val shouldSeed = conn.prepareStatement(
                "INSERT OR IGNORE INTO repository_overlay_state(target_snapshot) VALUES (?)",
            ).use { statement ->
                statement.setString(1, manifest.target.directoryName)
                statement.executeUpdate() == 1
            }
            val seededGraphState = if (shouldSeed) {
                conn.prepareStatement(
                    "INSERT OR IGNORE INTO repository_overlay_tombstones(path) VALUES (?)",
                ).use { statement ->
                    (manifest.tombstones + manifest.shards.keys).sorted().forEach { path ->
                        statement.setString(1, path)
                        statement.addBatch()
                    }
                    statement.executeBatch().any { updateCount -> updateCount != 0 }
                }
            } else {
                false
            }
            if (seededGraphState) {
                incrementGenerationInTransaction(conn)
            }
            conn.commit()
        } catch (error: Exception) {
            conn.rollback()
            throw error
        } finally {
            conn.autoCommit = previousAutoCommit
        }
    }

    internal fun markSchemaValidated(conn: Connection) {
        validatedSchemaConnection = conn
    }

    internal fun isSchemaValidated(conn: Connection): Boolean = validatedSchemaConnection === conn

    override fun close() {
        synchronized(connectionLock) {
            cachedConnection?.let { conn ->
                runCatching { conn.close() }
                cachedConnection = null
                validatedSchemaConnection = null
                loadedInterningDataVersion = null
            }
        }
    }

    internal fun readGenerationInTransaction(conn: Connection): SourceIndexGeneration =
        conn.prepareStatement("SELECT generation FROM schema_version LIMIT 1").use { stmt ->
            val rs = stmt.executeQuery()
            SourceIndexGeneration(if (rs.next()) rs.getLong(1) else 0L)
        }

    internal fun readGenerationOrNullInTransaction(conn: Connection): SourceIndexGeneration? = try {
        conn.prepareStatement("SELECT generation FROM schema_version LIMIT 1").use { stmt ->
            val rs = stmt.executeQuery()
            if (rs.next()) SourceIndexGeneration(rs.getLong(1)) else null
        }
    } catch (_: Exception) {
        null
    }

    internal fun writeGenerationInTransaction(conn: Connection, generation: SourceIndexGeneration) {
        conn.prepareStatement("UPDATE schema_version SET generation = ?").use { stmt ->
            stmt.setLong(1, generation.value)
            stmt.executeUpdate()
        }
    }

    internal fun incrementGenerationInTransaction(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.executeUpdate("UPDATE schema_version SET generation = generation + 1")
        }
    }

    internal fun commitManifestMutation(conn: Connection) {
        val committedCount = readManifestFileCount(conn)
        conn.commit()
        committedManifestFileCount.set(committedCount)
    }

    internal fun refreshManifestFileCount(conn: Connection) {
        committedManifestFileCount.set(readManifestFileCount(conn))
    }

    private fun readManifestFileCount(conn: Connection): NonNegativeInt =
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM file_manifest").use { rows ->
                check(rows.next()) { "SQLite did not return a manifest file count" }
                NonNegativeInt(rows.getInt(1))
            }
        }

    internal fun loadInterningTables(conn: Connection) {
        val dataVersion = readDataVersion(conn)
        if (loadedInterningDataVersion != dataVersion) {
            reloadInterningTables(conn, dataVersion)
        }
    }

    internal fun reloadInterningTables(conn: Connection) {
        loadedInterningDataVersion = null
        reloadInterningTables(conn, readDataVersion(conn))
    }

    private fun reloadInterningTables(conn: Connection, dataVersion: Long) {
        loadedInterningDataVersion = null
        try {
            pathCodec.reloadPrefixes(conn)
        } finally {
            fqCodec.reloadAll(conn)
        }
        loadedInterningDataVersion = dataVersion
    }

    internal fun rollbackAndReloadPrefixes(conn: Connection) {
        conn.rollback()
        runCatching { reloadInterningTables(conn) }
    }

    internal fun decodeNullablePath(
        rs: ResultSet,
        prefixColumn: Int,
        filenameColumn: Int,
    ): String? {
        val prefixId = rs.getNullableInt(prefixColumn) ?: return null
        val filename = requireNotNull(rs.getString(filenameColumn)) {
            "Path filename is missing for prefix_id=$prefixId"
        }
        return pathCodec.decode(prefixId, filename)
    }

    private fun readDataVersion(conn: Connection): Long =
        conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA main.data_version").use { rs ->
                check(rs.next()) { "SQLite did not return main.data_version" }
                rs.getLong(1)
            }
        }

    internal fun removeIneligibleSourceIndexRows(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute(
                """DELETE FROM symbol_references
                   WHERE src_filename NOT GLOB '*.kt'""",
            )
            stmt.execute(
                """UPDATE symbol_references
                   SET tgt_prefix_id = NULL,
                       tgt_filename = NULL,
                       target_offset = NULL
                   WHERE tgt_filename IS NOT NULL
                     AND tgt_filename NOT GLOB '*.kt'""",
            )
            for (table in listOf(
                "declarations",
                "identifier_paths",
                "file_gradle_source_sets",
                "file_gradle_projects",
                "file_metadata",
                "file_imports",
                "file_wildcard_imports",
                "file_manifest",
                "pending_updates",
            )) {
                stmt.execute("DELETE FROM $table WHERE filename NOT GLOB '*.kt'")
            }
        }
    }

    internal companion object {
        const val PENDING_UPDATE_RETENTION_MS = 7L * 24 * 60 * 60 * 1_000
        const val absolutePathPrefix = "__kast_abs__/"
        const val sourceRootProbeFileName = ".kast-source-root-probe.kt"
        private const val REPOSITORY_OVERLAY_FILE = "repository-overlay.json"
    }
}

internal fun ResultSet.getNullableInt(column: Int): Int? =
    getObject(column)?.let { (it as Number).toInt() }
