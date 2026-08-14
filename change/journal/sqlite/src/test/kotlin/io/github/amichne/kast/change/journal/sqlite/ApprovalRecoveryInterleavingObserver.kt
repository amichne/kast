package io.github.amichne.kast.change.journal.sqlite

import java.nio.file.Path
import java.sql.DriverManager

internal class ApprovalRecoveryInterleavingObserver(
    private val database: Path,
    private val planId: String,
    private val targetPath: String,
    private val beforeSha256: String,
    private val beforeContentBase64: String,
) : SqliteJournalConnectionObserver {
    var observedStage: String? = null
        private set

    override fun opened() = Unit

    override fun closed() = Unit

    override fun afterTransitionWrite(operation: SqliteJournalTransitionOperation) {
        if (operation != SqliteJournalTransitionOperation.APPROVAL) return
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            observedStage = connection.prepareStatement(
                "SELECT stage FROM add_declaration_plan WHERE plan_id = ?",
            ).use { statement ->
                statement.setString(1, planId)
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getString("stage")
                }
            }
            if (observedStage == "APPROVED") {
                connection.prepareStatement(
                    """INSERT INTO add_declaration_recovery(
                        plan_id, state_version, prior_stage, prior_version, target_path,
                        before_sha256, before_content_base64, mutation_progress
                    ) VALUES (?, 2, 'APPROVED', 1, ?, ?, ?, 'NOT_BEGUN')""",
                ).use { statement ->
                    statement.setString(1, planId)
                    statement.setString(2, targetPath)
                    statement.setString(3, beforeSha256)
                    statement.setString(4, beforeContentBase64)
                    check(statement.executeUpdate() == 1)
                }
            }
        }
    }
}
