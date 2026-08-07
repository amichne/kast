package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileInventoryEntry
import io.github.amichne.kast.indexstore.api.index.FileStageFailureCode
import io.github.amichne.kast.indexstore.api.index.FileStageFailureAttemptCount
import io.github.amichne.kast.indexstore.api.index.FileStageInputFingerprint
import io.github.amichne.kast.indexstore.api.index.FileStageLimitation
import io.github.amichne.kast.indexstore.api.index.FileStageOutcome
import io.github.amichne.kast.indexstore.api.index.FileStageOutcomeStatus
import io.github.amichne.kast.indexstore.api.index.FileStageScopeCoverage
import io.github.amichne.kast.indexstore.api.index.FileStageVersion
import io.github.amichne.kast.indexstore.api.index.FileStageVersions
import io.github.amichne.kast.indexstore.api.index.FileStageWorkReason
import io.github.amichne.kast.indexstore.api.index.PendingFileStage
import io.github.amichne.kast.indexstore.api.index.WorkspaceSourcePath
import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import io.github.amichne.kast.indexstore.store.cache.defaultCacheJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.sql.Connection

internal class FileStageInventoryStore(
    private val state: SqliteSourceIndexStoreState,
    private val mutations: SourceIndexFileMutations,
    private val semanticGraph: SemanticGraphWriter,
) {
    private val pathCodec get() = state.pathCodec
    private val reader = FileStageStateReader(state)
    private val inboundReferences = InboundReferenceInvalidator()
    private val statements = FileStageInventoryStatements(state)

    fun reconcileFileInventory(
        entries: Collection<FileInventoryEntry>,
        versions: FileStageVersions,
    ) {
        val desired = entries.associateBy(FileInventoryEntry::path)
        require(desired.size == entries.size) { "File-stage inventory paths must be unique" }
        synchronized(state.writeLock) {
            val conn = state.connection()
            state.loadInterningTables(conn)
            val current = reader.readInventoryInTransaction(conn)
            val desiredState = desired.mapValues { (_, entry) -> PersistedFileInventoryState.from(entry, versions) }
            val inventoryStateMatches = current.mapValues { (_, row) -> row.state } == desiredState
            val hasCommittedInventorySnapshot =
                state.readGenerationInTransaction(conn) != SourceIndexGeneration(0)
            if (inventoryStateMatches && hasCommittedInventorySnapshot) return
            val resolutionInputsChanged = desired.any { (path, entry) ->
                current[path]?.contentHash != entry.contentHash.value
            }

            state.writeTransaction(impact = SourceIndexMutationImpact.MANIFEST) { transaction ->
                mutations.internPathsInTransaction(conn, desired.keys.map(WorkspaceSourcePath::toDatabasePath))
                if (resolutionInputsChanged) statements.invalidateLimitedRelationshipOutcomesInTransaction(transaction)
                current.keys.minus(desired.keys).forEach { removedPath ->
                    removeInventoryInTransaction(transaction, removedPath, current.getValue(removedPath))
                }
                desired.toSortedMap().forEach { (path, entry) ->
                    current[path]?.let { previous ->
                        if (previous.contentHash != entry.contentHash.value) {
                            deleteOutcomeRowsInTransaction(transaction, path)
                            deleteSemanticGraphFileInTransaction(transaction, path)
                            inboundReferences.detachAndInvalidateInTransaction(
                                transaction,
                                previous.prefixId,
                                previous.filename,
                            )
                            mutations.deleteFileContentInTransaction(
                                transaction,
                                previous.prefixId,
                                previous.filename,
                            )
                        } else if (previous.module != entry.module) {
                            deleteOutcomeRowsInTransaction(transaction, path)
                        }
                    }
                    statements.upsertInventoryInTransaction(transaction, entry, versions)
                }
                recomputeModuleProgressInTransaction(transaction)
                state.incrementGenerationInTransaction(transaction)
            }
        }
    }

    fun reconcileRemovedFileInventory(paths: Collection<WorkspaceSourcePath>) {
        val removedPaths = paths.distinct()
        if (removedPaths.isEmpty()) return
        synchronized(state.writeLock) {
            val conn = state.connection()
            val current = removedPaths.mapNotNull { path ->
                reader.inventoryScopeInTransaction(conn, path)?.let { row -> path to row }
            }
            if (current.isEmpty()) return@synchronized
            state.writeTransaction(impact = SourceIndexMutationImpact.MANIFEST) { transaction ->
                current.forEach { (path, row) -> removeInventoryInTransaction(transaction, path, row) }
                recomputeModuleProgressInTransaction(transaction)
                state.incrementGenerationInTransaction(transaction)
            }
        }
    }

    fun pendingFileStages(stage: FileIndexStage): List<PendingFileStage> =
        synchronized(state.writeLock) {
            val conn = state.connection()
            state.loadInterningTables(conn)
            val versionColumn = statements.desiredVersionColumn(stage)
            val manifest = state.readTable(SourceIndexReadTable.FILE_MANIFEST)
            val outcomes = state.readTable(SourceIndexReadTable.FILE_STAGE_OUTCOMES)
            conn.prepareStatement(
                """SELECT manifest.prefix_id, manifest.filename, manifest.content_hash, manifest.$versionColumn
                   FROM $manifest manifest
                   LEFT JOIN $outcomes outcomes
                     ON outcomes.prefix_id = manifest.prefix_id
                    AND outcomes.filename = manifest.filename
                    AND outcomes.stage = ?
                   WHERE manifest.content_hash IS NOT NULL
                     AND manifest.$versionColumn IS NOT NULL
                     AND (
                         outcomes.stage IS NULL
                         OR outcomes.content_hash != manifest.content_hash
                         OR outcomes.stage_version != manifest.$versionColumn
                         OR outcomes.outcome_status = 'FAILED'
                     )""",
            ).use { statement ->
                statement.setString(1, stage.name)
                val rows = statement.executeQuery()
                buildList {
                    while (rows.next()) {
                        add(
                            PendingFileStage(
                                path = state.requireWorkspaceSourcePath(
                                    pathCodec.decode(rows.getInt(1), rows.getString(2)),
                                ),
                                contentHash = FileContentHash.parse(rows.getString(3)),
                                stage = stage,
                                version = FileStageVersion.parse(rows.getString(4)),
                            ),
                        )
                    }
                }.sortedBy(PendingFileStage::path)
            }
        }

    fun retryableLimitedRelationshipStages(): List<PendingFileStage> =
        retryableLimitedFileStages(FileIndexStage.RELATIONSHIPS)

    fun retryableLimitedSemanticGraphStages(): List<PendingFileStage> =
        retryableLimitedFileStages(FileIndexStage.SEMANTIC_GRAPH)

    private fun retryableLimitedFileStages(stage: FileIndexStage): List<PendingFileStage> =
        synchronized(state.writeLock) {
            require(stage != FileIndexStage.SOURCE) { "Source work does not support limited retries" }
            val conn = state.connection()
            state.loadInterningTables(conn)
            val versionColumn = statements.desiredVersionColumn(stage)
            val manifest = state.readTable(SourceIndexReadTable.FILE_MANIFEST)
            val outcomes = state.readTable(SourceIndexReadTable.FILE_STAGE_OUTCOMES)
            conn.prepareStatement(
                """SELECT manifest.prefix_id, manifest.filename, manifest.content_hash,
                          manifest.$versionColumn, outcomes.stage_input_fingerprint, outcomes.limitations_json
                   FROM $manifest manifest
                   JOIN $outcomes outcomes
                     ON outcomes.prefix_id = manifest.prefix_id
                    AND outcomes.filename = manifest.filename
                    AND outcomes.stage = ?
                   WHERE outcomes.content_hash = manifest.content_hash
                     AND outcomes.stage_version = manifest.$versionColumn
                     AND outcomes.outcome_status = 'LIMITED'""",
            ).use { statement ->
                statement.setString(1, stage.name)
                val rows = statement.executeQuery()
                buildList {
                    while (rows.next()) {
                        val limitations =
                            defaultCacheJson.decodeFromString<List<FileStageLimitation>>(rows.getString(6))
                        if (FileStageLimitation.PSI_UNAVAILABLE !in limitations) continue
                        add(
                            PendingFileStage(
                                path = state.requireWorkspaceSourcePath(
                                    pathCodec.decode(rows.getInt(1), rows.getString(2)),
                                ),
                                contentHash = FileContentHash.parse(rows.getString(3)),
                                stage = stage,
                                version = FileStageVersion.parse(rows.getString(4)),
                                inputFingerprint = rows.getString(5)?.let(FileStageInputFingerprint::parse),
                                reason = FileStageWorkReason.LIMITED_RETRY,
                            ),
                        )
                    }
                }.sortedBy(PendingFileStage::path)
            }
        }

    fun pendingFileStage(
        path: WorkspaceSourcePath,
        contentHash: FileContentHash,
        stage: FileIndexStage,
        version: FileStageVersion,
        inputFingerprint: FileStageInputFingerprint? = null,
    ): PendingFileStage? = synchronized(state.writeLock) {
        val conn = state.connection()
        val outcome = reader.readOutcomeInTransaction(conn, path, stage)
        val exactOutcome = outcome?.takeIf {
            it.contentHash == contentHash &&
                it.version == version &&
                it.inputFingerprint == inputFingerprint
        }
        when {
            exactOutcome?.status == FileStageOutcomeStatus.LIMITED &&
                FileStageLimitation.PSI_UNAVAILABLE in exactOutcome.limitations ->
                PendingFileStage(
                    path,
                    contentHash,
                    stage,
                    version,
                    inputFingerprint,
                    FileStageWorkReason.LIMITED_RETRY,
                )
            exactOutcome != null && exactOutcome.status != FileStageOutcomeStatus.FAILED -> null
            else -> PendingFileStage(path, contentHash, stage, version, inputFingerprint)
        }
    }

    fun fileStageOutcome(path: WorkspaceSourcePath, stage: FileIndexStage): FileStageOutcome? =
        reader.fileStageOutcome(path, stage)

    fun fileStageScopeCoverage(stage: FileIndexStage, path: WorkspaceSourcePath): FileStageScopeCoverage =
        reader.fileStageScopeCoverage(stage, path)

    fun fileStageScopeCoverage(
        stage: FileIndexStage,
        paths: Collection<WorkspaceSourcePath>,
    ): FileStageScopeCoverage =
        reader.fileStageScopeCoverage(stage, paths)

    internal fun requireCurrentWorkInTransaction(
        conn: Connection,
        work: PendingFileStage,
        inventoryRequired: Boolean,
    ) {
        val inventory = reader.inventoryScopeInTransaction(conn, work.path)
        check(!inventoryRequired || inventory != null) { "Pending ${work.stage} work is not in current inventory" }
        inventory?.let { row ->
            check(row.contentHash == work.contentHash.value) { "Pending file content changed before commit" }
            check(row.version(work.stage) == work.version.value) { "Pending file stage version changed before commit" }
        }
        val outcome = reader.readOutcomeInTransaction(conn, work.path, work.stage)
        val exactOutcome = outcome?.takeIf {
            it.contentHash == work.contentHash &&
                it.version == work.version &&
                it.inputFingerprint == work.inputFingerprint
        }
        when (work.reason) {
            FileStageWorkReason.PENDING ->
                check(exactOutcome == null || exactOutcome.status == FileStageOutcomeStatus.FAILED) {
                    "File stage is no longer pending"
                }
            FileStageWorkReason.LIMITED_RETRY ->
                check(
                    exactOutcome?.status == FileStageOutcomeStatus.LIMITED &&
                        FileStageLimitation.PSI_UNAVAILABLE in exactOutcome.limitations,
                ) { "Limited file stage is no longer retryable" }
        }
    }

    internal fun nextFailureAttemptInTransaction(
        conn: Connection,
        work: PendingFileStage,
        code: FileStageFailureCode,
    ): FileStageFailureAttemptCount {
        if (code != FileStageFailureCode.PSI_UNAVAILABLE) return FileStageFailureAttemptCount.of(1)
        val outcome = reader.readOutcomeInTransaction(conn, work.path, work.stage)
            ?: return FileStageFailureAttemptCount.of(1)
        if (outcome.contentHash != work.contentHash ||
            outcome.version != work.version ||
            outcome.inputFingerprint != work.inputFingerprint
        ) return FileStageFailureAttemptCount.of(1)
        val matches = when (outcome.status) {
            FileStageOutcomeStatus.FAILED -> outcome.failure?.code == code
            FileStageOutcomeStatus.LIMITED ->
                work.reason == FileStageWorkReason.LIMITED_RETRY &&
                    FileStageLimitation.PSI_UNAVAILABLE in outcome.limitations
            FileStageOutcomeStatus.COMPLETE,
            FileStageOutcomeStatus.EXTERNAL_BOUNDARY,
            -> false
        }
        return if (matches) outcome.failureAttemptCount.next() else FileStageFailureAttemptCount.of(1)
    }

    internal fun writeOutcomeInTransaction(
        conn: Connection,
        work: PendingFileStage,
        limitations: List<FileStageLimitation>,
        failureAttemptCount: FileStageFailureAttemptCount = FileStageFailureAttemptCount.NONE,
    ) {
        val canonicalLimitations = limitations.distinct().sortedBy(FileStageLimitation::name)
        val (prefixId, filename) = pathCodec.encodeOrCreate(conn, work.path.toDatabasePath())
        conn.prepareStatement(
            """INSERT INTO file_stage_outcomes(
                   prefix_id, filename, stage, content_hash, stage_version, stage_input_fingerprint,
                   outcome_status, limitations_json, failure_id, failure_code, failure_message,
                   failure_attempt_count
               ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?)
               ON CONFLICT(prefix_id, filename, stage) DO UPDATE SET
                   content_hash = excluded.content_hash,
                   stage_version = excluded.stage_version,
                   stage_input_fingerprint = excluded.stage_input_fingerprint,
                   outcome_status = excluded.outcome_status,
                   limitations_json = excluded.limitations_json,
                   failure_id = NULL,
                   failure_code = NULL,
                   failure_message = NULL,
                   failure_attempt_count = excluded.failure_attempt_count""",
        ).use { statement ->
            statement.setInt(1, prefixId)
            statement.setString(2, filename)
            statement.setString(3, work.stage.name)
            statement.setString(4, work.contentHash.value)
            statement.setString(5, work.version.value)
            statement.setString(6, work.inputFingerprint?.value)
            statement.setString(
                7,
                if (canonicalLimitations.isEmpty()) {
                    FileStageOutcomeStatus.COMPLETE.name
                } else {
                    FileStageOutcomeStatus.LIMITED.name
                },
            )
            statement.setString(8, defaultCacheJson.encodeToString(canonicalLimitations))
            statement.setInt(9, failureAttemptCount.value)
            statement.executeUpdate()
        }
    }

    internal fun deleteOutcomeRowsInTransaction(conn: Connection, path: WorkspaceSourcePath) {
        state.loadInterningTables(conn)
        val encoded = pathCodec.encodeIfInterned(path.toDatabasePath()) ?: return
        conn.prepareStatement("DELETE FROM file_stage_outcomes WHERE prefix_id = ? AND filename = ?").use { statement ->
            statement.setInt(1, encoded.first)
            statement.setString(2, encoded.second)
            statement.executeUpdate()
        }
    }

    private fun removeInventoryInTransaction(
        conn: Connection,
        path: WorkspaceSourcePath,
        row: PersistedFileInventory,
    ) {
        deleteOutcomeRowsInTransaction(conn, path)
        deleteSemanticGraphFileInTransaction(conn, path)
        inboundReferences.detachAndInvalidateInTransaction(conn, row.prefixId, row.filename)
        mutations.deleteFileRowsInTransaction(conn, row.prefixId, row.filename)
    }

    private fun deleteSemanticGraphFileInTransaction(conn: Connection, path: WorkspaceSourcePath) {
        semanticGraph.deleteSemanticGraphFileInTransaction(
            conn,
            SemanticGraphSourcePath.parse(path.relative.value),
        )
    }

    internal fun deleteOutcomeInTransaction(
        conn: Connection,
        path: WorkspaceSourcePath,
        stage: FileIndexStage,
    ) {
        state.loadInterningTables(conn)
        val encoded = pathCodec.encodeIfInterned(path.toDatabasePath()) ?: return
        conn.prepareStatement(
            "DELETE FROM file_stage_outcomes WHERE prefix_id = ? AND filename = ? AND stage = ?",
        ).use { statement ->
            statement.setInt(1, encoded.first)
            statement.setString(2, encoded.second)
            statement.setString(3, stage.name)
            statement.executeUpdate()
        }
    }

    internal fun recomputeModuleProgressInTransaction(conn: Connection) =
        statements.recomputeModuleProgressInTransaction(conn)
}
