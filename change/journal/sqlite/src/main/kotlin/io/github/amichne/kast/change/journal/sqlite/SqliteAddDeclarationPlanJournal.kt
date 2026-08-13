package io.github.amichne.kast.change.journal.sqlite

import io.github.amichne.kast.change.contract.AddDeclarationPlanCodec
import io.github.amichne.kast.change.contract.AddDeclarationPlanId
import io.github.amichne.kast.change.contract.PlannedAddDeclaration
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanJournal
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanJournalFailure
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanStage
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanStateVersion
import io.github.amichne.kast.change.journal.contract.ApproveAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.ApproveAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.LoadAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.PersistedAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.RawAddDeclarationPlanApprovalEvidence
import io.github.amichne.kast.change.journal.contract.StoreAddDeclarationPlanResult
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet

sealed interface SqliteAddDeclarationPlanJournalOpenFailure {
    data class InvalidDatabasePath(
        val failure: AddDeclarationJournalDatabasePathFailure,
    ) : SqliteAddDeclarationPlanJournalOpenFailure

    data object StorageUnavailable : SqliteAddDeclarationPlanJournalOpenFailure
}

sealed interface SqliteAddDeclarationPlanJournalOpenResult {
    data class Opened(
        val journal: SqliteAddDeclarationPlanJournal,
    ) : SqliteAddDeclarationPlanJournalOpenResult

    data class Rejected(
        val failure: SqliteAddDeclarationPlanJournalOpenFailure,
    ) : SqliteAddDeclarationPlanJournalOpenResult
}

class SqliteAddDeclarationPlanJournal private constructor(
    private val connections: SqliteJournalConnections,
) : AddDeclarationPlanJournal {
    override fun store(plan: PlannedAddDeclaration): StoreAddDeclarationPlanResult = storageResult(
        unavailable = {
            StoreAddDeclarationPlanResult.Rejected(
                AddDeclarationPlanJournalFailure.StorageUnavailable,
            )
        },
    ) {
        connections.use { connection ->
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
            val loaded = connection.loadRecord(plan.planId)
            when {
                loaded == null -> StoreAddDeclarationPlanResult.Rejected(
                    AddDeclarationPlanJournalFailure.CorruptRecord,
                )
                loaded.plan != plan -> StoreAddDeclarationPlanResult.Rejected(
                    AddDeclarationPlanJournalFailure.PlanIdCollision(plan.planId),
                )
                inserted == 1 -> StoreAddDeclarationPlanResult.Stored(
                    loaded as? PersistedAddDeclarationPlan.AwaitingApproval
                    ?: return@use StoreAddDeclarationPlanResult.Rejected(
                        AddDeclarationPlanJournalFailure.CorruptRecord,
                    ),
                )
                else -> StoreAddDeclarationPlanResult.Existing(loaded)
            }
        }
    }

    override fun load(planId: AddDeclarationPlanId): LoadAddDeclarationPlanResult = storageResult(
        unavailable = {
            LoadAddDeclarationPlanResult.Rejected(
                AddDeclarationPlanJournalFailure.StorageUnavailable,
            )
        },
    ) {
        connections.use { connection ->
            connection.loadRecord(planId)?.let(LoadAddDeclarationPlanResult::Found)
            ?: if (connection.recordExists(planId)) {
                LoadAddDeclarationPlanResult.Rejected(
                    AddDeclarationPlanJournalFailure.CorruptRecord,
                )
            } else {
                LoadAddDeclarationPlanResult.NotFound(planId)
            }
        }
    }

    override fun approve(
        command: ApproveAddDeclarationPlan,
    ): ApproveAddDeclarationPlanResult = storageResult(
        unavailable = {
            ApproveAddDeclarationPlanResult.Rejected(
                AddDeclarationPlanJournalFailure.StorageUnavailable,
            )
        },
    ) {
        connections.use { connection ->
            val nextVersion = command.expectedVersion.next().valueOrNull()
                              ?: return@use ApproveAddDeclarationPlanResult.Rejected(
                                  AddDeclarationPlanJournalFailure.StateVersionExhausted(
                                      command.planId,
                                  ),
                              )
            val updated = connection.prepareStatement(
                """UPDATE add_declaration_plan SET
                    stage = 'APPROVED', state_version = ?,
                    prior_stage = 'AWAITING_APPROVAL', prior_version = ?,
                    approval_plan_id = ?, approval_by = ?, approval_sha256 = ?
                WHERE plan_id = ? AND stage = 'AWAITING_APPROVAL' AND state_version = ?""",
            ).use { statement ->
                statement.setLong(1, nextVersion.value)
                statement.setLong(2, command.expectedVersion.value)
                statement.setString(3, command.evidence.planId.value)
                statement.setString(4, command.evidence.approvedBy.value)
                statement.setString(5, command.evidence.evidenceSha256.value)
                statement.setString(6, command.planId.value)
                statement.setLong(7, command.expectedVersion.value)
                statement.executeUpdate()
            }
            val actual = connection.loadRecord(command.planId)
            if (updated == 1) {
                val approved = actual as? PersistedAddDeclarationPlan.Approved
                               ?: return@use ApproveAddDeclarationPlanResult.Rejected(
                                   AddDeclarationPlanJournalFailure.CorruptRecord,
                               )
                ApproveAddDeclarationPlanResult.Approved(approved)
            } else if (actual == null) {
                if (connection.recordExists(command.planId)) {
                    ApproveAddDeclarationPlanResult.Rejected(
                        AddDeclarationPlanJournalFailure.CorruptRecord,
                    )
                } else {
                    ApproveAddDeclarationPlanResult.Rejected(
                        AddDeclarationPlanJournalFailure.PlanNotFound(command.planId),
                    )
                }
            } else {
                ApproveAddDeclarationPlanResult.Rejected(
                    AddDeclarationPlanJournalFailure.PriorStateMismatch(
                        planId = command.planId,
                        expectedStage = AddDeclarationPlanStage.AWAITING_APPROVAL,
                        expectedVersion = command.expectedVersion,
                        actualStage = actual.stage,
                        actualVersion = actual.version,
                    ),
                )
            }
        }
    }

    private fun <T> storageResult(
        unavailable: () -> T,
        block: () -> T,
    ): T = try {
        block()
    } catch (_: Exception) {
        unavailable()
    }

    companion object {
        /**
         * Proof transition:
         * `Path -> SqliteAddDeclarationPlanJournalOpenResult`.
         *
         * An opened result establishes a canonical admitted database and initialized constrained
         * schema, with the initialization connection already closed. Expected failures are closed
         * by `SqliteAddDeclarationPlanJournalOpenFailure`; raw paths are extracted only by the
         * SQLite connection boundary.
         */
        fun open(
            databasePath: Path,
            observer: SqliteJournalConnectionObserver = SqliteJournalConnectionObserver.Disabled,
        ): SqliteAddDeclarationPlanJournalOpenResult {
            val database = when (val admitted = AddDeclarationJournalDatabase.admit(databasePath)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return SqliteAddDeclarationPlanJournalOpenResult.Rejected(
                    SqliteAddDeclarationPlanJournalOpenFailure.InvalidDatabasePath(admitted.failure),
                )
            }
            val connections = SqliteJournalConnections(database, observer)
            return try {
                connections.use(Connection::initializeAddDeclarationPlanJournal)
                SqliteAddDeclarationPlanJournalOpenResult.Opened(
                    SqliteAddDeclarationPlanJournal(connections),
                )
            } catch (_: Exception) {
                SqliteAddDeclarationPlanJournalOpenResult.Rejected(
                    SqliteAddDeclarationPlanJournalOpenFailure.StorageUnavailable,
                )
            }
        }
    }
}

/**
 * Proof transition: stored row selected by `AddDeclarationPlanId` to a revalidated
 * `PersistedAddDeclarationPlan`, or `null` when absent or corrupt.
 *
 * Establishes canonical plan bytes, exact PlanId and generation, and a replayed closed lifecycle
 * transition. Raw columns are extracted only inside [ResultSet.toRecordOrNull].
 */
private fun Connection.loadRecord(planId: AddDeclarationPlanId): PersistedAddDeclarationPlan? =
    prepareStatement(
        """SELECT plan_id, plan_bytes, source_generation, stage, state_version,
            prior_stage, prior_version, approval_plan_id, approval_by, approval_sha256
        FROM add_declaration_plan WHERE plan_id = ?""",
    ).use { statement ->
        statement.setString(1, planId.value)
        statement.executeQuery().use { rows ->
            if (!rows.next()) null else rows.toRecordOrNull(planId)
        }
    }

private fun Connection.recordExists(planId: AddDeclarationPlanId): Boolean =
    prepareStatement("SELECT 1 FROM add_declaration_plan WHERE plan_id = ?").use { statement ->
        statement.setString(1, planId.value)
        statement.executeQuery().use(ResultSet::next)
    }

/**
 * Proof transition: one SQLite result row plus expected PlanId to `PersistedAddDeclarationPlan` or
 * `null` for any malformed, mismatched, or unsupported stored state.
 *
 * Establishes canonical detached plan evidence and the exact KIP-032 lifecycle transition. Raw
 * column extraction is permitted only in this record-decoder boundary.
 */
private fun ResultSet.toRecordOrNull(expectedPlanId: AddDeclarationPlanId): PersistedAddDeclarationPlan? {
    val storedPlanId = AddDeclarationPlanId.parse(getString("plan_id")).valueOrNull() ?: return null
    val plan = AddDeclarationPlanCodec.decode(getString("plan_bytes")).valueOrNull() ?: return null
    if (storedPlanId != expectedPlanId || storedPlanId != plan.planId) return null
    if (getLong("source_generation") != plan.generation.value) return null
    val version = AddDeclarationPlanStateVersion.parse(getLong("state_version")).valueOrNull()
                  ?: return null
    return when (getString("stage")) {
        AddDeclarationPlanStage.AWAITING_APPROVAL.name ->
            PersistedAddDeclarationPlan.restoreAwaiting(plan, version).valueOrNull()
        AddDeclarationPlanStage.APPROVED.name -> {
            if (getString("prior_stage") != AddDeclarationPlanStage.AWAITING_APPROVAL.name) return null
            val priorVersionValue = getLong("prior_version")
            if (wasNull()) return null
            val priorVersion = AddDeclarationPlanStateVersion.parse(priorVersionValue).valueOrNull()
                               ?: return null
            val approval = RawAddDeclarationPlanApprovalEvidence(
                planId = getString("approval_plan_id") ?: return null,
                approvedBy = getString("approval_by") ?: return null,
                evidenceSha256 = getString("approval_sha256") ?: return null,
            ).refine().valueOrNull() ?: return null
            PersistedAddDeclarationPlan.restoreApproved(
                plan = plan,
                currentVersion = version,
                priorVersion = priorVersion,
                evidence = approval,
            ).valueOrNull()
        }
        else -> null
    }
}

private fun <T, F> Refinement<T, F>.valueOrNull(): T? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}
