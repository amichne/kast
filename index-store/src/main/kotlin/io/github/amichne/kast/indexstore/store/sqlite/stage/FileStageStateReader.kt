package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileInventoryEntry
import io.github.amichne.kast.indexstore.api.index.FileStageInputFingerprint
import io.github.amichne.kast.indexstore.api.index.FileStageFailure
import io.github.amichne.kast.indexstore.api.index.FileStageFailureAttemptCount
import io.github.amichne.kast.indexstore.api.index.FileStageFailureCode
import io.github.amichne.kast.indexstore.api.index.FileStageFailureId
import io.github.amichne.kast.indexstore.api.index.FileStageLimitation
import io.github.amichne.kast.indexstore.api.index.FileStageOutcome
import io.github.amichne.kast.indexstore.api.index.FileStageOutcomeStatus
import io.github.amichne.kast.indexstore.api.index.FileStageScopeCoverage
import io.github.amichne.kast.indexstore.api.index.FileStageVersion
import io.github.amichne.kast.indexstore.api.index.FileStageVersions
import io.github.amichne.kast.indexstore.api.index.SourceIndexModuleIdentity
import io.github.amichne.kast.indexstore.api.index.WorkspaceSourcePath
import io.github.amichne.kast.indexstore.store.cache.defaultCacheJson
import kotlinx.serialization.decodeFromString
import java.sql.Connection

internal class FileStageStateReader(
    private val state: SqliteSourceIndexStoreState,
) {
    private val pathCodec get() = state.pathCodec

    fun fileStageOutcome(path: WorkspaceSourcePath, stage: FileIndexStage): FileStageOutcome? =
        synchronized(state.writeLock) {
            readOutcomeInTransaction(state.connection(), path, stage)
        }

    fun fileStageScopeCoverage(
        stage: FileIndexStage,
        path: WorkspaceSourcePath,
    ): FileStageScopeCoverage = synchronized(state.writeLock) {
        val conn = state.connection()
        val scope = inventoryScopeInTransaction(conn, path)
        val files = when {
            scope == null -> listOf(classifyStandaloneOutcome(readOutcomeInTransaction(conn, path, stage)))
            scope.module == null -> listOf(classifyInventoryFile(conn, scope, stage))
            else -> readInventoryInTransaction(conn).values
                .filter { row -> row.module == scope.module }
                .map { row -> classifyInventoryFile(conn, row, stage) }
        }
        coverage(files)
    }

    fun fileStageScopeCoverage(
        stage: FileIndexStage,
        paths: Collection<WorkspaceSourcePath>,
    ): FileStageScopeCoverage = synchronized(state.writeLock) {
        val conn = state.connection()
        val inventory = readInventoryInTransaction(conn)
        coverage(
            paths.distinct().map { path ->
                inventory[path]?.let { row -> classifyInventoryFile(conn, row, stage) }
                    ?: classifyStandaloneOutcome(readOutcomeInTransaction(conn, path, stage))
            },
        )
    }

    internal fun readInventoryInTransaction(conn: Connection): Map<WorkspaceSourcePath, PersistedFileInventory> {
        state.loadInterningTables(conn)
        return conn.createStatement().use { statement ->
            val rows = statement.executeQuery(
                """SELECT prefix_id, filename, last_modified_millis, content_hash,
                          desired_source_version, desired_relationships_version, desired_semantic_graph_version,
                          module_name, source_set
                   FROM file_manifest""",
            )
            buildMap {
                while (rows.next()) {
                    val row = PersistedFileInventory(
                        prefixId = rows.getInt(1),
                        filename = rows.getString(2),
                        lastModifiedMillis = rows.getLong(3),
                        contentHash = rows.getString(4),
                        sourceVersion = rows.getString(5),
                        relationshipsVersion = rows.getString(6),
                        semanticGraphVersion = rows.getString(7),
                        module = decodeSourceIndexModuleIdentity(rows.getString(8), rows.getString(9)),
                    )
                    put(state.requireWorkspaceSourcePath(pathCodec.decode(row.prefixId, row.filename)), row)
                }
            }
        }
    }

    internal fun readOutcomeInTransaction(
        conn: Connection,
        path: WorkspaceSourcePath,
        stage: FileIndexStage,
    ): FileStageOutcome? {
        state.loadInterningTables(conn)
        val encoded = pathCodec.encodeIfInterned(path.toDatabasePath()) ?: return null
        return conn.prepareStatement(
            """SELECT content_hash, stage_version, stage_input_fingerprint, outcome_status, limitations_json,
                      failure_id, failure_code, failure_message, failure_attempt_count
               FROM file_stage_outcomes
               WHERE prefix_id = ? AND filename = ? AND stage = ?""",
        ).use { statement ->
            statement.setInt(1, encoded.first)
            statement.setString(2, encoded.second)
            statement.setString(3, stage.name)
            val rows = statement.executeQuery()
            if (!rows.next()) return@use null
            FileStageOutcome(
                path = path,
                contentHash = FileContentHash.parse(rows.getString(1)),
                stage = stage,
                version = FileStageVersion.parse(rows.getString(2)),
                inputFingerprint = rows.getString(3)?.let(FileStageInputFingerprint::parse),
                status = FileStageOutcomeStatus.valueOf(rows.getString(4)),
                limitations = defaultCacheJson.decodeFromString<List<FileStageLimitation>>(rows.getString(5)),
                failure = rows.getString(6)?.let { failureId ->
                    FileStageFailure(
                        id = FileStageFailureId.parse(failureId),
                        code = FileStageFailureCode.valueOf(checkNotNull(rows.getString(7))),
                        message = checkNotNull(rows.getString(8)),
                    )
                },
                failureAttemptCount = FileStageFailureAttemptCount.of(rows.getInt(9)),
            )
        }
    }

    internal fun inventoryScopeInTransaction(
        conn: Connection,
        path: WorkspaceSourcePath,
    ): PersistedFileInventory? {
        state.loadInterningTables(conn)
        val encoded = pathCodec.encodeIfInterned(path.toDatabasePath()) ?: return null
        return conn.prepareStatement(
            """SELECT last_modified_millis, content_hash,
                      desired_source_version, desired_relationships_version, desired_semantic_graph_version,
                      module_name, source_set
               FROM file_manifest
               WHERE prefix_id = ? AND filename = ?""",
        ).use { statement ->
            statement.setInt(1, encoded.first)
            statement.setString(2, encoded.second)
            val rows = statement.executeQuery()
            if (!rows.next()) return@use null
            PersistedFileInventory(
                prefixId = encoded.first,
                filename = encoded.second,
                lastModifiedMillis = rows.getLong(1),
                contentHash = rows.getString(2),
                sourceVersion = rows.getString(3),
                relationshipsVersion = rows.getString(4),
                semanticGraphVersion = rows.getString(5),
                module = decodeSourceIndexModuleIdentity(rows.getString(6), rows.getString(7)),
            )
        }
    }

    private fun classifyInventoryFile(
        conn: Connection,
        row: PersistedFileInventory,
        stage: FileIndexStage,
    ): ClassifiedFile {
        val hash = row.contentHash ?: return ClassifiedFile.Pending
        val version = row.version(stage) ?: return ClassifiedFile.Pending
        val outcome = readOutcomeInTransaction(
            conn,
            state.requireWorkspaceSourcePath(pathCodec.decode(row.prefixId, row.filename)),
            stage,
        )
            ?: return ClassifiedFile.Pending
        if (outcome.contentHash.value != hash || outcome.version.value != version) return ClassifiedFile.Stale
        return when (outcome.status) {
            FileStageOutcomeStatus.COMPLETE -> ClassifiedFile.Complete
            FileStageOutcomeStatus.LIMITED -> ClassifiedFile.Limited(outcome.limitations)
            FileStageOutcomeStatus.FAILED -> ClassifiedFile.Failed
            FileStageOutcomeStatus.EXTERNAL_BOUNDARY -> ClassifiedFile.External
        }
    }

    private fun classifyStandaloneOutcome(outcome: FileStageOutcome?): ClassifiedFile = when (outcome?.status) {
        null -> ClassifiedFile.Pending
        FileStageOutcomeStatus.COMPLETE -> ClassifiedFile.Complete
        FileStageOutcomeStatus.LIMITED -> ClassifiedFile.Limited(outcome.limitations)
        FileStageOutcomeStatus.FAILED -> ClassifiedFile.Failed
        FileStageOutcomeStatus.EXTERNAL_BOUNDARY -> ClassifiedFile.External
    }

    private fun coverage(files: List<ClassifiedFile>): FileStageScopeCoverage {
        val complete = files.count { it == ClassifiedFile.Complete }
        if (files.isNotEmpty() && complete == files.size) return FileStageScopeCoverage.Complete(files.size)
        return FileStageScopeCoverage.Limited(
            totalFiles = files.size,
            completeFiles = complete,
            pendingFiles = files.count { it == ClassifiedFile.Pending },
            staleFiles = files.count { it == ClassifiedFile.Stale },
            limitedFiles = files.count { it is ClassifiedFile.Limited },
            failedFiles = files.count { it == ClassifiedFile.Failed },
            externalFiles = files.count { it == ClassifiedFile.External },
            limitations = files.filterIsInstance<ClassifiedFile.Limited>()
                .flatMap(ClassifiedFile.Limited::limitations)
                .distinct()
                .sortedBy(FileStageLimitation::name),
        )
    }

    private sealed interface ClassifiedFile {
        data object Complete : ClassifiedFile
        data object Pending : ClassifiedFile
        data object Stale : ClassifiedFile
        data class Limited(val limitations: List<FileStageLimitation>) : ClassifiedFile
        data object Failed : ClassifiedFile
        data object External : ClassifiedFile
    }
}

internal data class PersistedFileInventory(
    val prefixId: Int,
    val filename: String,
    val lastModifiedMillis: Long,
    val contentHash: String?,
    val sourceVersion: String?,
    val relationshipsVersion: String?,
    val semanticGraphVersion: String?,
    val module: SourceIndexModuleIdentity?,
) {
    val state: PersistedFileInventoryState
        get() = PersistedFileInventoryState(
            lastModifiedMillis = lastModifiedMillis,
            contentHash = contentHash,
            sourceVersion = sourceVersion,
            relationshipsVersion = relationshipsVersion,
            semanticGraphVersion = semanticGraphVersion,
            module = module,
        )

    fun version(stage: FileIndexStage): String? = when (stage) {
        FileIndexStage.SOURCE -> sourceVersion
        FileIndexStage.RELATIONSHIPS -> relationshipsVersion
        FileIndexStage.SEMANTIC_GRAPH -> semanticGraphVersion
    }
}

internal data class PersistedFileInventoryState(
    val lastModifiedMillis: Long,
    val contentHash: String?,
    val sourceVersion: String?,
    val relationshipsVersion: String?,
    val semanticGraphVersion: String?,
    val module: SourceIndexModuleIdentity?,
) {
    companion object {
        fun from(entry: FileInventoryEntry, versions: FileStageVersions): PersistedFileInventoryState =
            PersistedFileInventoryState(
                lastModifiedMillis = entry.lastModifiedMillis,
                contentHash = entry.contentHash.value,
                sourceVersion = versions.source.value,
                relationshipsVersion = versions.relationships.value,
                semanticGraphVersion = versions.semanticGraph.value,
                module = entry.module,
            )
    }
}
