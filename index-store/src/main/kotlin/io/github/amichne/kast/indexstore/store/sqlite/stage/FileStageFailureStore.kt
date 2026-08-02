package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileStageFailure
import io.github.amichne.kast.indexstore.api.index.FileStageFailureCode
import io.github.amichne.kast.indexstore.api.index.FileStageFailureAttemptCount
import io.github.amichne.kast.indexstore.api.index.FileStageFailureId
import io.github.amichne.kast.indexstore.api.index.FileStageOutcomeStatus
import io.github.amichne.kast.indexstore.api.index.PendingFileStage
import io.github.amichne.kast.indexstore.api.index.WorkspaceSourcePath
import java.sql.Connection

internal class FileStageFailureStore(
    private val state: SqliteSourceIndexStoreState,
) {
    private val pathCodec get() = state.pathCodec

    internal fun writeFailureOutcomeInTransaction(
        conn: Connection,
        work: PendingFileStage,
        code: FileStageFailureCode,
        message: String,
        attemptCount: FileStageFailureAttemptCount,
    ): FileStageFailure {
        require(attemptCount.value > 0) { "Failure outcomes require a positive attempt count" }
        val failure = FileStageFailure(FileStageFailureId.create(), code, message)
        val (prefixId, filename) = pathCodec.encodeOrCreate(conn, work.path.toDatabasePath())
        conn.prepareStatement(
            """INSERT INTO file_stage_outcomes(
                   prefix_id, filename, stage, content_hash, stage_version, stage_input_fingerprint,
                   outcome_status, limitations_json, failure_id, failure_code, failure_message,
                   failure_attempt_count
               ) VALUES (?, ?, ?, ?, ?, ?, ?, '[]', ?, ?, ?, ?)
               ON CONFLICT(prefix_id, filename, stage) DO UPDATE SET
                   content_hash = excluded.content_hash,
                   stage_version = excluded.stage_version,
                   stage_input_fingerprint = excluded.stage_input_fingerprint,
                   outcome_status = excluded.outcome_status,
                   limitations_json = excluded.limitations_json,
                   failure_id = excluded.failure_id,
                   failure_code = excluded.failure_code,
                   failure_message = excluded.failure_message,
                   failure_attempt_count = excluded.failure_attempt_count""",
        ).use { statement ->
            statement.setInt(1, prefixId)
            statement.setString(2, filename)
            statement.setString(3, work.stage.name)
            statement.setString(4, work.contentHash.value)
            statement.setString(5, work.version.value)
            statement.setString(6, work.inputFingerprint?.value)
            statement.setString(7, FileStageOutcomeStatus.FAILED.name)
            statement.setString(8, failure.id.value)
            statement.setString(9, failure.code.name)
            statement.setString(10, failure.message)
            statement.setInt(11, attemptCount.value)
            statement.executeUpdate()
        }
        return failure
    }

    internal fun currentFailureByIdInTransaction(
        conn: Connection,
        failureId: FileStageFailureId,
    ): CurrentFileStageFailure? {
        state.loadInterningTables(conn)
        return conn.prepareStatement(
            """SELECT outcomes.prefix_id, outcomes.filename, outcomes.stage, outcomes.outcome_status,
                      outcomes.content_hash, outcomes.failure_code, outcomes.failure_message
               FROM file_stage_outcomes outcomes
               JOIN file_manifest manifest
                 ON manifest.prefix_id = outcomes.prefix_id
                AND manifest.filename = outcomes.filename
               WHERE outcomes.failure_id = ?
                 AND outcomes.content_hash = manifest.content_hash
                 AND outcomes.stage_version = CASE outcomes.stage
                     WHEN 'SOURCE' THEN manifest.desired_source_version
                     WHEN 'RELATIONSHIPS' THEN manifest.desired_relationships_version
                     WHEN 'SEMANTIC_GRAPH' THEN manifest.desired_semantic_graph_version
                 END""",
        ).use { statement ->
            statement.setString(1, failureId.value)
            val rows = statement.executeQuery()
            if (!rows.next()) return@use null
            CurrentFileStageFailure(
                path = state.requireWorkspaceSourcePath(pathCodec.decode(rows.getInt(1), rows.getString(2))),
                stage = FileIndexStage.valueOf(rows.getString(3)),
                status = FileStageOutcomeStatus.valueOf(rows.getString(4)),
                contentHash = FileContentHash.parse(rows.getString(5)),
                failure = FileStageFailure(
                    id = failureId,
                    code = FileStageFailureCode.valueOf(rows.getString(6)),
                    message = rows.getString(7),
                ),
            )
        }
    }

    internal fun markFailureExternalInTransaction(
        conn: Connection,
        failureId: FileStageFailureId,
    ) {
        conn.prepareStatement(
            """UPDATE file_stage_outcomes
               SET outcome_status = ?
               WHERE failure_id = ? AND outcome_status = ?""",
        ).use { statement ->
            statement.setString(1, FileStageOutcomeStatus.EXTERNAL_BOUNDARY.name)
            statement.setString(2, failureId.value)
            statement.setString(3, FileStageOutcomeStatus.FAILED.name)
            check(statement.executeUpdate() == 1) { "Current file-stage failure changed before externalization" }
        }
    }
}

internal data class CurrentFileStageFailure(
    val path: WorkspaceSourcePath,
    val stage: FileIndexStage,
    val status: FileStageOutcomeStatus,
    val contentHash: FileContentHash,
    val failure: FileStageFailure,
)
