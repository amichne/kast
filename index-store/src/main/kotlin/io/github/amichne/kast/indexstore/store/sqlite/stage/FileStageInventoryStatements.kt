package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileInventoryEntry
import io.github.amichne.kast.indexstore.api.index.FileStageOutcomeStatus
import io.github.amichne.kast.indexstore.api.index.FileStageVersions
import java.sql.Connection

internal class FileStageInventoryStatements(private val state: SqliteSourceIndexStoreState) {
    fun recomputeModuleProgressInTransaction(conn: Connection) {
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

    fun upsertInventoryInTransaction(
        conn: Connection,
        entry: FileInventoryEntry,
        versions: FileStageVersions,
    ) {
        val (prefixId, filename) = state.pathCodec.encode(entry.path.toDatabasePath())
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
            statement.setString(8, entry.module?.toDatabaseModuleName())
            statement.setString(9, entry.module?.sourceSet?.value)
            statement.executeUpdate()
        }
    }

    fun invalidateLimitedRelationshipOutcomesInTransaction(conn: Connection) {
        conn.prepareStatement(
            "DELETE FROM file_stage_outcomes WHERE stage = ? AND outcome_status = ?",
        ).use { statement ->
            statement.setString(1, FileIndexStage.RELATIONSHIPS.name)
            statement.setString(2, FileStageOutcomeStatus.LIMITED.name)
            statement.executeUpdate()
        }
    }

    fun desiredVersionColumn(stage: FileIndexStage): String = when (stage) {
        FileIndexStage.SOURCE -> "desired_source_version"
        FileIndexStage.RELATIONSHIPS -> "desired_relationships_version"
        FileIndexStage.SEMANTIC_GRAPH -> "desired_semantic_graph_version"
    }
}
