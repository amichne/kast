package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.indexstore.api.index.SourceIndexFilePolicy
import io.github.amichne.kast.indexstore.store.codec.PathInterningCodec
import io.github.amichne.kast.indexstore.store.codec.StringInterningCodec
import io.github.amichne.kast.indexstore.store.codec.StringInterningDomain
import io.github.amichne.kast.indexstore.store.jdbc.SqliteJdbcDriverBootstrap
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

private sealed interface WorkspaceWriteAuthority {
    data object Idle : WorkspaceWriteAuthority

    data class Active(val session: WorkspaceWriteSession) : WorkspaceWriteAuthority
}

internal class SqliteSourceIndexStoreState(
    workspaceIdentity: WorkspaceIdentity,
    internal val pageReadObserver: SourceIndexPageReadObserver,
    private val access: SqliteSourceIndexStoreAccess = SqliteSourceIndexStoreAccess.READ_WRITE,
) : AutoCloseable {
    internal val workspaceRoot: Path = workspaceIdentity.workspaceRootPath
    internal val normalizedWorkspaceRoot: NormalizedPath = NormalizedPath.of(workspaceRoot)
    internal val sourceFilePolicy = SourceIndexFilePolicy.forWorkspace(workspaceRoot)
    internal val dbPath: Path = workspaceIdentity.sourceIndexDatabaseFile
    private val repositoryOverlay = when (
        val resolution = RepositoryOverlayState.resolve(
            databasePath = workspaceIdentity.sourceIndexDatabasePath,
            repository = workspaceIdentity.repository,
        )
    ) {
        is RepositoryOverlayStateResolution.Resolved -> resolution.state
        is RepositoryOverlayStateResolution.Rejected -> throw RepositoryOverlayAuthorityException(resolution.failure)
    }
    internal val repositoryOverlayPublication get() = repositoryOverlay.publication
    internal val pathCodec = PathInterningCodec(normalizedWorkspaceRoot.toJavaPath())
    internal val fqCodec = StringInterningCodec(StringInterningDomain.FQ_NAME)
    internal val connectionLock = Any()
    internal val writeLock = Any()
    internal val schema: SqliteSourceIndexSchema by lazy { SqliteSourceIndexSchema(this) }
    private val writerLease: SourceIndexWriterLease? =
        if (access == SqliteSourceIndexStoreAccess.READ_WRITE) SourceIndexWriterLease.acquire(dbPath) else null

    @Volatile
    private var cachedConnection: Connection? = null
    @Volatile
    private var validatedSchemaConnection: Connection? = null
    @Volatile
    private var loadedInterningDataVersion: Long? = null
    private val committedManifestFileCount = AtomicReference(NonNegativeInt(0))
    private var workspaceWriteAuthority: WorkspaceWriteAuthority = WorkspaceWriteAuthority.Idle

    internal fun dbExists(): Boolean = Files.isRegularFile(dbPath)

    internal fun prepareManifestFileCount() {
        if (!dbExists()) return
        synchronized(writeLock) {
            if (dbExists()) runCatching { connection() }
        }
    }

    internal fun committedManifestFileCount(): NonNegativeInt = committedManifestFileCount.get()

    internal fun beginWorkspaceWrite(): WorkspaceWriteSession = synchronized(writeLock) {
        check(access == SqliteSourceIndexStoreAccess.READ_WRITE) {
            "Read-only source index cannot begin a workspace write"
        }
        check(workspaceWriteAuthority == WorkspaceWriteAuthority.Idle) { "A workspace write is already active" }
        val conn = connection()
        check(conn.autoCommit) { "Workspace write requires an idle SQLite connection" }
        conn.autoCommit = false
        WorkspaceWriteSession(UUID.randomUUID()).also { session ->
            workspaceWriteAuthority = WorkspaceWriteAuthority.Active(session)
        }
    }

    internal fun <T> inspectWorkspaceWrite(
        session: WorkspaceWriteSession,
        inspect: (Connection) -> T,
    ): T = synchronized(writeLock) {
        requireActiveWorkspaceWrite(session)
        inspect(connection())
    }

    internal fun <T> commitWorkspaceWrite(
        session: WorkspaceWriteSession,
        publish: (Connection) -> T,
    ): T = synchronized(writeLock) {
        requireActiveWorkspaceWrite(session)
        val conn = connection()
        try {
            val result = publish(conn)
            val committedCount = readManifestFileCount(conn)
            conn.commit()
            workspaceWriteAuthority = WorkspaceWriteAuthority.Idle
            committedManifestFileCount.set(committedCount)
            loadedInterningDataVersion = null
            result
        } catch (failure: Throwable) {
            rollbackWorkspaceWrite(conn, session)
            throw failure
        } finally {
            conn.autoCommit = true
        }
    }

    internal fun discardWorkspaceWrite(session: WorkspaceWriteSession) = synchronized(writeLock) {
        requireActiveWorkspaceWrite(session)
        val conn = connection()
        try {
            rollbackWorkspaceWrite(conn, session)
        } finally {
            conn.autoCommit = true
        }
    }

    internal fun <T> writeTransaction(
        impact: SourceIndexMutationImpact = SourceIndexMutationImpact.CONTENT_ONLY,
        write: (Connection) -> T,
    ): T = synchronized(writeLock) {
        val conn = connection()
        when (val authority = workspaceWriteAuthority) {
            is WorkspaceWriteAuthority.Active -> {
                requireActiveWorkspaceWrite(authority.session)
                val savepoint = conn.setSavepoint()
                try {
                    write(conn).also { conn.releaseSavepoint(savepoint) }
                } catch (failure: Throwable) {
                    conn.rollback(savepoint)
                    runCatching { reloadInterningTables(conn) }
                    throw failure
                }
            }

            WorkspaceWriteAuthority.Idle -> {
                check(conn.autoCommit) { "SQLite write transaction requires an idle connection" }
                conn.autoCommit = false
                try {
                    val result = write(conn)
                    conn.commit()
                    if (impact == SourceIndexMutationImpact.MANIFEST) refreshManifestFileCount(conn)
                    result
                } catch (failure: Throwable) {
                    conn.rollback()
                    runCatching { reloadInterningTables(conn) }
                    throw failure
                } finally {
                    conn.autoCommit = true
                }
            }
        }
    }

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
            check(access == SqliteSourceIndexStoreAccess.READ_WRITE || Files.isRegularFile(dbPath)) {
                "Read-only source index does not exist: $dbPath"
            }
            if (access == SqliteSourceIndexStoreAccess.READ_WRITE) Files.createDirectories(dbPath.parent)
            SqliteJdbcDriverBootstrap.ensureRegistered()
            val connectionUrl = if (access == SqliteSourceIndexStoreAccess.READ_ONLY) {
                "jdbc:sqlite:${dbPath.toUri().toASCIIString()}?mode=ro"
            } else {
                "jdbc:sqlite:$dbPath"
            }
            val conn = DriverManager.getConnection(connectionUrl)
            try {
                conn.createStatement().use { stmt ->
                    if (access == SqliteSourceIndexStoreAccess.READ_WRITE) {
                        stmt.execute("PRAGMA journal_mode=WAL")
                        stmt.execute("PRAGMA synchronous=NORMAL")
                        stmt.execute("PRAGMA wal_autocheckpoint=1000")
                    }
                    stmt.execute("PRAGMA busy_timeout=5000")
                    stmt.execute("PRAGMA cache_size=-64000")
                    stmt.execute("PRAGMA mmap_size=268435456")
                    stmt.execute("PRAGMA temp_store=FILE")
                    stmt.execute("PRAGMA foreign_keys=ON")
                }
                when (val attachment = repositoryOverlay.attachBase(conn)) {
                    RepositoryBaseAttachmentResolution.Attached -> Unit
                    is RepositoryBaseAttachmentResolution.Rejected ->
                        throw RepositoryOverlayAuthorityException(attachment.failure)
                }
                if (schema.readSchemaVersion(conn) == null) {
                    check(access == SqliteSourceIndexStoreAccess.READ_WRITE) {
                        "Read-only source index has no schema: $dbPath"
                    }
                    conn.autoCommit = false
                    schema.createAllTables(conn)
                    conn.commit()
                    conn.autoCommit = true
                }
                if (requireCurrentSchema) {
                    schema.validateCurrentSchema(conn)
                    if (access == SqliteSourceIndexStoreAccess.READ_WRITE) {
                        initializeRepositoryOverlay(conn)
                    } else {
                        repositoryOverlay.installReadAuthority(conn)
                    }
                    reloadInterningTables(conn)
                    refreshManifestFileCount(conn)
                    if (access == SqliteSourceIndexStoreAccess.READ_ONLY) {
                        conn.createStatement().use { statement -> statement.execute("PRAGMA query_only=ON") }
                    }
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

    internal fun initializeRepositoryOverlay(conn: Connection) =
        repositoryOverlay.initialize(conn, ::incrementGenerationInTransaction).also {
            repositoryOverlay.installReadAuthority(conn)
        }

    internal fun readTable(table: SourceIndexReadTable): SqlReadRelation = repositoryOverlay.readTable(table)

    internal fun clearRepositoryOverlayTombstone(conn: Connection, path: SemanticGraphSourcePath) =
        repositoryOverlay.clearTombstone(conn, path)

    internal fun recordRepositoryOverlayTombstone(conn: Connection, path: SemanticGraphSourcePath) =
        repositoryOverlay.recordTombstone(conn, path)

    internal fun markSchemaValidated(conn: Connection) {
        validatedSchemaConnection = conn
    }

    internal fun isSchemaValidated(conn: Connection): Boolean = validatedSchemaConnection === conn

    override fun close() {
        try {
            synchronized(connectionLock) {
                cachedConnection?.let { conn ->
                    when (val authority = workspaceWriteAuthority) {
                        is WorkspaceWriteAuthority.Active ->
                            runCatching { rollbackWorkspaceWrite(conn, authority.session) }
                        WorkspaceWriteAuthority.Idle -> Unit
                    }
                    runCatching { conn.close() }
                    cachedConnection = null
                    validatedSchemaConnection = null
                    loadedInterningDataVersion = null
                }
            }
        } finally {
            writerLease?.close()
        }
    }

    private fun requireActiveWorkspaceWrite(session: WorkspaceWriteSession) {
        require(workspaceWriteAuthority == WorkspaceWriteAuthority.Active(session)) {
            "Workspace write session is not active"
        }
    }

    private fun rollbackWorkspaceWrite(conn: Connection, session: WorkspaceWriteSession) {
        conn.rollback()
        workspaceWriteAuthority = WorkspaceWriteAuthority.Idle
        runCatching { reloadInterningTables(conn) }
        refreshManifestFileCount(conn)
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

    internal fun refreshManifestFileCount(conn: Connection) {
        committedManifestFileCount.set(readManifestFileCount(conn))
    }

    private fun readManifestFileCount(conn: Connection): NonNegativeInt =
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM ${readTable(SourceIndexReadTable.FILE_MANIFEST)}").use { rows ->
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
        when (val resolution = repositoryOverlay.loadInterningAliases(conn, pathCodec, fqCodec)) {
            RepositoryInterningAliasResolution.Loaded -> Unit
            is RepositoryInterningAliasResolution.Rejected -> throw RepositoryOverlayAuthorityException(
                RepositoryOverlayAuthorityFailure.InterningAliasesRejected(resolution.failure),
            )
        }
        loadedInterningDataVersion = dataVersion
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

    internal companion object {
        const val PENDING_UPDATE_RETENTION_MS = 7L * 24 * 60 * 60 * 1_000
        const val absolutePathPrefix = "__kast_abs__/"
        const val sourceRootProbeFileName = ".kast-source-root-probe.kt"
    }
}
