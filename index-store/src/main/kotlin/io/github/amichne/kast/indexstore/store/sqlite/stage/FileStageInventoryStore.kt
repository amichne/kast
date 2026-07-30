package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileInventoryEntry
import io.github.amichne.kast.indexstore.api.index.FileStageInputFingerprint
import io.github.amichne.kast.indexstore.api.index.FileStageLimitation
import io.github.amichne.kast.indexstore.api.index.FileStageOutcome
import io.github.amichne.kast.indexstore.api.index.FileStageOutcomeStatus
import io.github.amichne.kast.indexstore.api.index.FileStageScopeCoverage
import io.github.amichne.kast.indexstore.api.index.FileStageVersion
import io.github.amichne.kast.indexstore.api.index.FileStageVersions
import io.github.amichne.kast.indexstore.api.index.PendingFileStage
import io.github.amichne.kast.indexstore.api.index.SourceIndexFilePolicy
import io.github.amichne.kast.indexstore.store.cache.defaultCacheJson
import kotlinx.serialization.encodeToString
import java.sql.Connection

internal class FileStageInventoryStore(
    private val state: SqliteSourceIndexStoreState,
    private val mutations: SourceIndexFileMutations,
) {
    private val pathCodec get() = state.pathCodec
    private val reader = FileStageStateReader(state)
    private val inboundReferences = InboundReferenceInvalidator()

    fun reconcileFileInventory(
        entries: Collection<FileInventoryEntry>,
        versions: FileStageVersions,
    ) {
        require(entries.all { entry -> SourceIndexFilePolicy.isEligible(entry.path) }) {
            "File-stage inventory accepts Kotlin source files only"
        }
        val desired = entries.associateBy(FileInventoryEntry::path)
        require(desired.size == entries.size) { "File-stage inventory paths must be unique" }
        synchronized(state.writeLock) {
            val conn = state.connection()
            state.loadInterningTables(conn)
            val current = reader.readInventoryInTransaction(conn)
            val desiredState = desired.mapValues { (_, entry) -> PersistedFileInventoryState.from(entry, versions) }
            if (current.mapValues { (_, row) -> row.state } == desiredState) return
            val resolutionInputsChanged = desired.any { (path, entry) ->
                current[path]?.contentHash != entry.contentHash.value
            }

            conn.autoCommit = false
            try {
                mutations.internPathsInTransaction(conn, desired.keys)
                if (resolutionInputsChanged) invalidateLimitedRelationshipOutcomesInTransaction(conn)
                current.keys.minus(desired.keys).forEach { removedPath ->
                    removeInventoryInTransaction(conn, removedPath, current.getValue(removedPath))
                }
                desired.toSortedMap().forEach { (path, entry) ->
                    current[path]?.let { previous ->
                        if (previous.contentHash != entry.contentHash.value) {
                            deleteOutcomeRowsInTransaction(conn, path)
                            inboundReferences.detachAndInvalidateInTransaction(
                                conn,
                                previous.prefixId,
                                previous.filename,
                            )
                            mutations.deleteFileContentInTransaction(conn, previous.prefixId, previous.filename)
                        } else if (previous.moduleName != entry.moduleName || previous.sourceSet != entry.sourceSet) {
                            deleteOutcomeRowsInTransaction(conn, path)
                        }
                    }
                    upsertInventoryInTransaction(conn, entry, versions)
                }
                recomputeModuleProgressInTransaction(conn)
                state.incrementGenerationInTransaction(conn)
                conn.commit()
            } catch (failure: Exception) {
                state.rollbackAndReloadPrefixes(conn)
                throw failure
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun reconcileRemovedFileInventory(paths: Collection<String>) {
        require(paths.all(SourceIndexFilePolicy::isEligible)) {
            "Removed file-stage inventory accepts Kotlin source files only"
        }
        val removedPaths = paths.distinct()
        if (removedPaths.isEmpty()) return
        synchronized(state.writeLock) {
            val conn = state.connection()
            val current = removedPaths.mapNotNull { path ->
                reader.inventoryScopeInTransaction(conn, path)?.let { row -> path to row }
            }
            if (current.isEmpty()) return@synchronized
            conn.autoCommit = false
            try {
                current.forEach { (path, row) -> removeInventoryInTransaction(conn, path, row) }
                recomputeModuleProgressInTransaction(conn)
                state.incrementGenerationInTransaction(conn)
                conn.commit()
            } catch (failure: Exception) {
                state.rollbackAndReloadPrefixes(conn)
                throw failure
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun pendingFileStages(stage: FileIndexStage): List<PendingFileStage> =
        synchronized(state.writeLock) {
            val conn = state.connection()
            state.loadInterningTables(conn)
            val versionColumn = desiredVersionColumn(stage)
            conn.prepareStatement(
                """SELECT manifest.prefix_id, manifest.filename, manifest.content_hash, manifest.$versionColumn
                   FROM file_manifest manifest
                   LEFT JOIN file_stage_outcomes outcomes
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
                     )
                     AND NOT (
                         ? = 'SEMANTIC_GRAPH'
                         AND EXISTS (
                             SELECT 1
                             FROM file_stage_outcomes boundary
                             WHERE boundary.prefix_id = manifest.prefix_id
                               AND boundary.filename = manifest.filename
                               AND boundary.stage = 'RELATIONSHIPS'
                               AND boundary.content_hash = manifest.content_hash
                               AND boundary.stage_version = manifest.desired_relationships_version
                               AND boundary.outcome_status = 'EXTERNAL_BOUNDARY'
                         )
                     )""",
            ).use { statement ->
                statement.setString(1, stage.name)
                statement.setString(2, stage.name)
                val rows = statement.executeQuery()
                buildList {
                    while (rows.next()) {
                        add(
                            PendingFileStage(
                                path = pathCodec.decode(rows.getInt(1), rows.getString(2)),
                                contentHash = FileContentHash.parse(rows.getString(3)),
                                stage = stage,
                                version = FileStageVersion.parse(rows.getString(4)),
                            ),
                        )
                    }
                }.sortedBy(PendingFileStage::path)
            }
        }

    fun pendingFileStage(
        path: String,
        contentHash: FileContentHash,
        stage: FileIndexStage,
        version: FileStageVersion,
        inputFingerprint: FileStageInputFingerprint? = null,
    ): PendingFileStage? = synchronized(state.writeLock) {
        val conn = state.connection()
        val outcome = reader.readOutcomeInTransaction(conn, path, stage)
        val inventory = reader.inventoryScopeInTransaction(conn, path)
        val externalRelationshipBoundary = if (stage == FileIndexStage.SEMANTIC_GRAPH) {
            reader.readOutcomeInTransaction(conn, path, FileIndexStage.RELATIONSHIPS)
                ?.takeIf { relationship ->
                    relationship.status == FileStageOutcomeStatus.EXTERNAL_BOUNDARY &&
                        relationship.contentHash == contentHash &&
                        relationship.version.value == inventory?.relationshipsVersion
                }
        } else {
            null
        }
        if (externalRelationshipBoundary != null ||
            (outcome != null &&
            outcome.contentHash == contentHash &&
            outcome.version == version &&
            outcome.inputFingerprint == inputFingerprint &&
            outcome.status != FileStageOutcomeStatus.FAILED)
        ) {
            null
        } else {
            PendingFileStage(path, contentHash, stage, version, inputFingerprint)
        }
    }

    fun fileStageOutcome(path: String, stage: FileIndexStage): FileStageOutcome? =
        reader.fileStageOutcome(path, stage)

    fun fileStageScopeCoverage(stage: FileIndexStage, path: String): FileStageScopeCoverage =
        reader.fileStageScopeCoverage(stage, path)

    fun fileStageScopeCoverage(stage: FileIndexStage, paths: Collection<String>): FileStageScopeCoverage =
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
        check(
            outcome == null ||
                outcome.contentHash != work.contentHash ||
                outcome.version != work.version ||
                outcome.inputFingerprint != work.inputFingerprint ||
                outcome.status == FileStageOutcomeStatus.FAILED,
        ) { "File stage is no longer pending" }
    }

    internal fun writeOutcomeInTransaction(
        conn: Connection,
        work: PendingFileStage,
        limitations: List<FileStageLimitation>,
    ) {
        val canonicalLimitations = limitations.distinct().sortedBy(FileStageLimitation::name)
        val (prefixId, filename) = pathCodec.encodeOrCreate(conn, work.path)
        conn.prepareStatement(
            """INSERT INTO file_stage_outcomes(
                   prefix_id, filename, stage, content_hash, stage_version, stage_input_fingerprint,
                   outcome_status, limitations_json, failure_id, failure_code, failure_message
               ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL)
               ON CONFLICT(prefix_id, filename, stage) DO UPDATE SET
                   content_hash = excluded.content_hash,
                   stage_version = excluded.stage_version,
                   stage_input_fingerprint = excluded.stage_input_fingerprint,
                   outcome_status = excluded.outcome_status,
                   limitations_json = excluded.limitations_json,
                   failure_id = NULL,
                   failure_code = NULL,
                   failure_message = NULL""",
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
            statement.setString(8, defaultCacheJson.encodeToString(canonicalLimitations.map(FileStageLimitation::name)))
            statement.executeUpdate()
        }
    }

    internal fun deleteOutcomeRowsInTransaction(conn: Connection, path: String) {
        state.loadInterningTables(conn)
        val encoded = pathCodec.encodeIfInterned(path) ?: return
        conn.prepareStatement("DELETE FROM file_stage_outcomes WHERE prefix_id = ? AND filename = ?").use { statement ->
            statement.setInt(1, encoded.first)
            statement.setString(2, encoded.second)
            statement.executeUpdate()
        }
    }

    private fun removeInventoryInTransaction(
        conn: Connection,
        path: String,
        row: PersistedFileInventory,
    ) {
        deleteOutcomeRowsInTransaction(conn, path)
        inboundReferences.detachAndInvalidateInTransaction(conn, row.prefixId, row.filename)
        mutations.deleteFileRowsInTransaction(conn, row.prefixId, row.filename)
    }

    internal fun deleteOutcomeInTransaction(conn: Connection, path: String, stage: FileIndexStage) {
        state.loadInterningTables(conn)
        val encoded = pathCodec.encodeIfInterned(path) ?: return
        conn.prepareStatement(
            "DELETE FROM file_stage_outcomes WHERE prefix_id = ? AND filename = ? AND stage = ?",
        ).use { statement ->
            statement.setInt(1, encoded.first)
            statement.setString(2, encoded.second)
            statement.setString(3, stage.name)
            statement.executeUpdate()
        }
    }

    internal fun recomputeModuleProgressInTransaction(conn: Connection) {
        conn.createStatement().use { statement ->
            statement.execute("DELETE FROM module_index_progress")
            statement.execute(
                """INSERT INTO module_index_progress(
                       module_name, relationship_index_status, indexed_file_count, total_file_count, last_indexed_epoch_ms
                   )
                   SELECT manifest.module_name,
                          CASE
                              WHEN COUNT(*) = SUM(CASE
                                  WHEN outcomes.content_hash = manifest.content_hash
                                   AND outcomes.stage_version = manifest.desired_relationships_version
                                   AND outcomes.outcome_status = 'COMPLETE' THEN 1 ELSE 0 END)
                                  THEN 'COMPLETE'
                              WHEN SUM(CASE
                                  WHEN outcomes.content_hash = manifest.content_hash
                                   AND outcomes.stage_version = manifest.desired_relationships_version
                                   AND outcomes.outcome_status = 'FAILED' THEN 1 ELSE 0 END) > 0
                                  THEN 'FAILED'
                              WHEN COUNT(*) = SUM(CASE
                                  WHEN outcomes.content_hash = manifest.content_hash
                                   AND outcomes.stage_version = manifest.desired_relationships_version
                                   AND outcomes.outcome_status IN ('COMPLETE','LIMITED','EXTERNAL_BOUNDARY') THEN 1 ELSE 0 END)
                               AND SUM(CASE
                                  WHEN outcomes.content_hash = manifest.content_hash
                                   AND outcomes.stage_version = manifest.desired_relationships_version
                                   AND outcomes.outcome_status IN ('LIMITED','EXTERNAL_BOUNDARY') THEN 1 ELSE 0 END) > 0
                                  THEN 'DEGRADED'
                              WHEN SUM(CASE
                                  WHEN outcomes.content_hash = manifest.content_hash
                                   AND outcomes.stage_version = manifest.desired_relationships_version
                                  THEN 1 ELSE 0 END) > 0
                                  THEN 'INDEXING'
                              ELSE 'PENDING'
                          END,
                          SUM(CASE
                              WHEN outcomes.content_hash = manifest.content_hash
                               AND outcomes.stage_version = manifest.desired_relationships_version
                               AND outcomes.outcome_status IN ('COMPLETE','LIMITED','EXTERNAL_BOUNDARY') THEN 1 ELSE 0 END),
                          COUNT(*),
                          NULL
                   FROM file_manifest manifest
                   LEFT JOIN file_stage_outcomes outcomes
                     ON outcomes.prefix_id = manifest.prefix_id
                    AND outcomes.filename = manifest.filename
                    AND outcomes.stage = 'RELATIONSHIPS'
                   WHERE manifest.module_name IS NOT NULL
                   GROUP BY manifest.module_name""",
            )
        }
    }

    private fun upsertInventoryInTransaction(
        conn: Connection,
        entry: FileInventoryEntry,
        versions: FileStageVersions,
    ) {
        val (prefixId, filename) = pathCodec.encode(entry.path)
        conn.prepareStatement(
            """INSERT INTO file_manifest(
                   prefix_id, filename, last_modified_millis, content_hash,
                   desired_source_version, desired_relationships_version, desired_semantic_graph_version,
                   module_name, source_set
               ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(prefix_id, filename) DO UPDATE SET
                   last_modified_millis = excluded.last_modified_millis,
                   content_hash = excluded.content_hash,
                   desired_source_version = excluded.desired_source_version,
                   desired_relationships_version = excluded.desired_relationships_version,
                   desired_semantic_graph_version = excluded.desired_semantic_graph_version,
                   module_name = excluded.module_name,
                   source_set = excluded.source_set""",
        ).use { statement ->
            statement.setInt(1, prefixId)
            statement.setString(2, filename)
            statement.setLong(3, entry.lastModifiedMillis)
            statement.setString(4, entry.contentHash.value)
            statement.setString(5, versions.source.value)
            statement.setString(6, versions.relationships.value)
            statement.setString(7, versions.semanticGraph.value)
            statement.setString(8, entry.moduleName)
            statement.setString(9, entry.sourceSet)
            statement.executeUpdate()
        }
    }

    private fun invalidateLimitedRelationshipOutcomesInTransaction(conn: Connection) {
        conn.prepareStatement(
            "DELETE FROM file_stage_outcomes WHERE stage = ? AND outcome_status = ?",
        ).use { statement ->
            statement.setString(1, FileIndexStage.RELATIONSHIPS.name)
            statement.setString(2, FileStageOutcomeStatus.LIMITED.name)
            statement.executeUpdate()
        }
    }

    private fun desiredVersionColumn(stage: FileIndexStage): String = when (stage) {
        FileIndexStage.SOURCE -> "desired_source_version"
        FileIndexStage.RELATIONSHIPS -> "desired_relationships_version"
        FileIndexStage.SEMANTIC_GRAPH -> "desired_semantic_graph_version"
    }
}
