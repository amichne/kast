package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import java.sql.Connection

internal class InboundReferenceInvalidator {
    fun detachAndInvalidateInTransaction(
        conn: Connection,
        targetPrefixId: Int,
        targetFilename: String,
    ) {
        val sourceFiles = conn.prepareStatement(
            """SELECT DISTINCT src_prefix_id, src_filename
               FROM symbol_references
               WHERE tgt_prefix_id = ? AND tgt_filename = ?""",
        ).use { statement ->
            statement.setInt(1, targetPrefixId)
            statement.setString(2, targetFilename)
            val rows = statement.executeQuery()
            buildList {
                while (rows.next()) add(rows.getInt(1) to rows.getString(2))
            }
        }
        if (sourceFiles.isEmpty()) return
        conn.prepareStatement(
            """UPDATE symbol_references
               SET tgt_prefix_id = NULL, tgt_filename = NULL, target_offset = NULL
               WHERE tgt_prefix_id = ? AND tgt_filename = ?""",
        ).use { statement ->
            statement.setInt(1, targetPrefixId)
            statement.setString(2, targetFilename)
            statement.executeUpdate()
        }
        conn.prepareStatement(
            """DELETE FROM file_stage_outcomes
               WHERE prefix_id = ? AND filename = ? AND stage = ?""",
        ).use { statement ->
            sourceFiles.forEach { (sourcePrefixId, sourceFilename) ->
                statement.setInt(1, sourcePrefixId)
                statement.setString(2, sourceFilename)
                statement.setString(3, FileIndexStage.RELATIONSHIPS.name)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }
}
