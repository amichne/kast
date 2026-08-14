package io.github.amichne.kast.change.journal.sqlite

import io.github.amichne.kast.change.contract.AddDeclarationPlanCodec
import io.github.amichne.kast.change.contract.PlannedAddDeclaration
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanJournalFailure
import io.github.amichne.kast.change.journal.contract.PersistedAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.StoreAddDeclarationPlanResult

/**
 * Proof transition: `PlannedAddDeclaration -> StoreAddDeclarationPlanResult`.
 *
 * A stored result establishes a durable awaiting-approval row whose decoded evidence is the exact
 * supplied plan. Expected failures are closed by `AddDeclarationPlanJournalFailure`; SQLite row
 * values are extracted only at the journal boundary.
 */
internal fun SqliteJournalConnections.store(
    plan: PlannedAddDeclaration,
): StoreAddDeclarationPlanResult = use { connection ->
    connection.autoCommit = false
    val encoded = AddDeclarationPlanCodec.encode(plan)
    val inserted = connection.prepareStatement(
        """INSERT OR IGNORE INTO add_declaration_plan(
            plan_id, plan_bytes, source_generation, stage, state_version
        ) VALUES (?, ?, ?, 'AWAITING_APPROVAL', 0)""",
    ).use { statement ->
        statement.setString(1, plan.planId.value)
        statement.setString(2, encoded)
        statement.setLong(3, plan.generation.value)
        statement.executeUpdate()
    }
    if (inserted == 1) {
        observeTransitionWrite(SqliteJournalTransitionOperation.PLAN_STORAGE)
    }
    val result = when (val loaded = connection.loadRecord(plan.planId)) {
        SqliteAddDeclarationPlanRecordLoad.Absent,
        SqliteAddDeclarationPlanRecordLoad.Corrupt,
            -> StoreAddDeclarationPlanResult.Rejected(
            AddDeclarationPlanJournalFailure.CorruptRecord,
        )
        is SqliteAddDeclarationPlanRecordLoad.Found -> when {
            loaded.record.plan != plan -> StoreAddDeclarationPlanResult.Rejected(
                AddDeclarationPlanJournalFailure.PlanIdCollision(plan.planId),
            )
            inserted == 1 -> when (
                val awaiting = loaded.record as? PersistedAddDeclarationPlan.AwaitingApproval
            ) {
                null -> StoreAddDeclarationPlanResult.Rejected(
                    AddDeclarationPlanJournalFailure.CorruptRecord,
                )
                else -> StoreAddDeclarationPlanResult.Stored(awaiting)
            }
            else -> StoreAddDeclarationPlanResult.Existing(loaded.record)
        }
    }
    if (result is StoreAddDeclarationPlanResult.Stored) {
        connection.commit()
    } else {
        connection.rollback()
    }
    result
}
