package io.github.amichne.kast.change.journal.sqlite

import io.github.amichne.kast.change.contract.AddDeclarationPlanCodec
import io.github.amichne.kast.change.contract.AddDeclarationPlanId
import io.github.amichne.kast.change.contract.PlannedAddDeclaration
import io.github.amichne.kast.change.journal.contract.AddDeclarationApplyJournal
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanJournalFailure
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanStage
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanStateVersion
import io.github.amichne.kast.change.journal.contract.AppliedUnverifiedAddDeclaration
import io.github.amichne.kast.change.journal.contract.ApproveAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.ApproveAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.BeginAddDeclarationApply
import io.github.amichne.kast.change.journal.contract.BeginAddDeclarationApplyResult
import io.github.amichne.kast.change.journal.contract.CompleteAddDeclarationApply
import io.github.amichne.kast.change.journal.contract.CompleteAddDeclarationApplyResult
import io.github.amichne.kast.change.journal.contract.LoadAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.PersistedAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.PrepareAddDeclarationRecovery
import io.github.amichne.kast.change.journal.contract.PrepareAddDeclarationRecoveryResult
import io.github.amichne.kast.change.journal.contract.RawAddDeclarationPlanApprovalEvidence
import io.github.amichne.kast.change.journal.contract.StoreAddDeclarationPlanResult
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationJournal
import io.github.amichne.kast.change.verify.spi.CompleteAddDeclarationVerification
import io.github.amichne.kast.change.verify.spi.CompleteAddDeclarationVerificationResult
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

internal sealed interface SqliteAddDeclarationPlanRecordLoad {
    data class Found(
        val record: PersistedAddDeclarationPlan,
    ) : SqliteAddDeclarationPlanRecordLoad

    data object Absent : SqliteAddDeclarationPlanRecordLoad

    data object Corrupt : SqliteAddDeclarationPlanRecordLoad
}

private enum class SqliteAddDeclarationPlanRecordDecodeFailure {
    CORRUPT,
}

class SqliteAddDeclarationPlanJournal private constructor(
    private val connections: SqliteJournalConnections,
) : AddDeclarationApplyJournal, AddDeclarationVerificationJournal {
    override fun store(plan: PlannedAddDeclaration): StoreAddDeclarationPlanResult = storageResult(
        unavailable = {
            StoreAddDeclarationPlanResult.Rejected(
                AddDeclarationPlanJournalFailure.StorageUnavailable,
            )
        },
    ) {
        connections.store(plan)
    }

    override fun load(planId: AddDeclarationPlanId): LoadAddDeclarationPlanResult = storageResult(
        unavailable = {
            LoadAddDeclarationPlanResult.Rejected(
                AddDeclarationPlanJournalFailure.StorageUnavailable,
            )
        },
    ) {
        connections.use { connection ->
            when (val loaded = connection.loadRecord(planId)) {
                is SqliteAddDeclarationPlanRecordLoad.Found ->
                    LoadAddDeclarationPlanResult.Found(loaded.record)
                SqliteAddDeclarationPlanRecordLoad.Absent ->
                    LoadAddDeclarationPlanResult.NotFound(planId)
                SqliteAddDeclarationPlanRecordLoad.Corrupt ->
                    LoadAddDeclarationPlanResult.Rejected(
                        AddDeclarationPlanJournalFailure.CorruptRecord,
                    )
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
            connection.autoCommit = false
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
            if (updated == 1) {
                connections.observeTransitionWrite(SqliteJournalTransitionOperation.APPROVAL)
            }
            val loaded = connection.loadRecord(command.planId)
            val result = if (updated == 1) {
                val approved = (loaded as? SqliteAddDeclarationPlanRecordLoad.Found)
                                   ?.record as? PersistedAddDeclarationPlan.Approved
                               ?: return@use ApproveAddDeclarationPlanResult.Rejected(
                                   AddDeclarationPlanJournalFailure.CorruptRecord,
                               )
                ApproveAddDeclarationPlanResult.Approved(approved)
            } else {
                when (loaded) {
                    SqliteAddDeclarationPlanRecordLoad.Corrupt ->
                        ApproveAddDeclarationPlanResult.Rejected(
                            AddDeclarationPlanJournalFailure.CorruptRecord,
                        )
                    SqliteAddDeclarationPlanRecordLoad.Absent ->
                        ApproveAddDeclarationPlanResult.Rejected(
                            AddDeclarationPlanJournalFailure.PlanNotFound(command.planId),
                        )
                    is SqliteAddDeclarationPlanRecordLoad.Found ->
                        ApproveAddDeclarationPlanResult.Rejected(
                            AddDeclarationPlanJournalFailure.PriorStateMismatch(
                                planId = command.planId,
                                expectedStage = AddDeclarationPlanStage.AWAITING_APPROVAL,
                                expectedVersion = command.expectedVersion,
                                actualStage = loaded.record.stage,
                                actualVersion = loaded.record.version,
                            ),
                        )
                }
            }
            if (result is ApproveAddDeclarationPlanResult.Approved) {
                connection.commit()
            } else {
                connection.rollback()
            }
            result
        }
    }

    override fun prepareRecovery(
        command: PrepareAddDeclarationRecovery,
    ): PrepareAddDeclarationRecoveryResult = connections.prepareRecovery(command)

    override fun beginApply(
        command: BeginAddDeclarationApply,
    ): BeginAddDeclarationApplyResult = connections.beginApply(command)

    override fun completeApply(
        command: CompleteAddDeclarationApply,
    ): CompleteAddDeclarationApplyResult = connections.completeApply(command)

    override fun completeVerification(
        command: CompleteAddDeclarationVerification,
    ): CompleteAddDeclarationVerificationResult = connections.completeVerification(command)

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
 * `SqliteAddDeclarationPlanRecordLoad`.
 *
 * `Found` establishes canonical plan bytes, exact PlanId and generation, and a replayed closed
 * lifecycle transition. `Absent` and `Corrupt` are distinct closed expected outcomes. Raw columns
 * are extracted only inside [ResultSet.toRecord].
 */
internal fun Connection.loadRecord(planId: AddDeclarationPlanId): SqliteAddDeclarationPlanRecordLoad =
    prepareStatement(
        """SELECT p.plan_id, p.plan_bytes, p.source_generation, p.stage, p.state_version,
            p.prior_stage, p.prior_version, p.approval_plan_id, p.approval_by, p.approval_sha256,
            r.plan_id AS recovery_plan_id, r.state_version AS recovery_state_version,
            r.prior_stage AS recovery_prior_stage, r.prior_version AS recovery_prior_version,
            r.target_path AS recovery_target_path, r.before_sha256 AS recovery_before_sha256,
            r.before_content_base64 AS recovery_before_content_base64,
            r.mutation_progress AS recovery_mutation_progress,
            a.plan_id AS apply_plan_id, a.stage AS apply_stage,
            a.state_version AS apply_state_version, a.prior_stage AS apply_prior_stage,
            a.prior_version AS apply_prior_version,
            a.observed_target_path AS apply_observed_target_path,
            a.after_sha256 AS apply_after_sha256,
            a.after_content_base64 AS apply_after_content_base64,
            v.plan_id AS verification_plan_id, v.stage AS verification_stage,
            v.state_version AS verification_state_version,
            v.prior_stage AS verification_prior_stage,
            v.prior_version AS verification_prior_version,
            v.publication_generation AS verification_publication_generation,
            v.publication_identity AS verification_publication_identity,
            v.verified_target_path AS verification_target_path,
            v.observed_start_offset AS verification_start_offset,
            v.observed_end_offset AS verification_end_offset,
            v.observed_package_name AS verification_package_name,
            v.observed_declaration_name AS verification_declaration_name,
            v.observed_declaration_kind AS verification_declaration_kind,
            v.verified_postimage_sha256 AS verification_postimage_sha256
        FROM add_declaration_plan p
        LEFT JOIN add_declaration_recovery r ON r.plan_id = p.plan_id
        LEFT JOIN add_declaration_apply a ON a.plan_id = p.plan_id
        LEFT JOIN add_declaration_verification v ON v.plan_id = p.plan_id
        WHERE p.plan_id = ?""",
    ).use { statement ->
        statement.setString(1, planId.value)
        statement.executeQuery().use { rows ->
            if (!rows.next()) {
                SqliteAddDeclarationPlanRecordLoad.Absent
            } else {
                when (val decoded = rows.toRecord(planId)) {
                    is Refinement.Refined -> SqliteAddDeclarationPlanRecordLoad.Found(decoded.value)
                    is Refinement.Rejected -> SqliteAddDeclarationPlanRecordLoad.Corrupt
                }
            }
        }
    }

/**
 * Proof transition: one SQLite result row plus expected PlanId to
 * `Refinement<PersistedAddDeclarationPlan, SqliteAddDeclarationPlanRecordDecodeFailure>`.
 *
 * Establishes canonical detached plan evidence and the exact KIP-032 lifecycle transition. Raw
 * column extraction is permitted only in this record-decoder boundary; malformed, mismatched, or
 * unsupported stored state is the closed `SqliteAddDeclarationPlanRecordDecodeFailure`.
 */
private fun ResultSet.toRecord(
    expectedPlanId: AddDeclarationPlanId,
): Refinement<PersistedAddDeclarationPlan, SqliteAddDeclarationPlanRecordDecodeFailure> {
    val storedPlanId = AddDeclarationPlanId.parse(getString("plan_id")).valueOrNull()
                       ?: return corruptRecord()
    val plan = AddDeclarationPlanCodec.decode(getString("plan_bytes")).valueOrNull()
               ?: return corruptRecord()
    if (storedPlanId != expectedPlanId || storedPlanId != plan.planId) return corruptRecord()
    if (getLong("source_generation") != plan.generation.value) return corruptRecord()
    val version = AddDeclarationPlanStateVersion.parse(getLong("state_version")).valueOrNull()
                  ?: return corruptRecord()
    return when (getString("stage")) {
        AddDeclarationPlanStage.AWAITING_APPROVAL.name ->
            when (val restored = PersistedAddDeclarationPlan.restoreAwaiting(plan, version)) {
                is Refinement.Refined -> Refinement.Refined(restored.value)
                is Refinement.Rejected -> corruptRecord()
            }
        AddDeclarationPlanStage.APPROVED.name -> {
            if (getString("prior_stage") != AddDeclarationPlanStage.AWAITING_APPROVAL.name) {
                return corruptRecord()
            }
            val priorVersionValue = getLong("prior_version")
            if (wasNull()) return corruptRecord()
            val priorVersion = AddDeclarationPlanStateVersion.parse(priorVersionValue).valueOrNull()
                               ?: return corruptRecord()
            val approval = RawAddDeclarationPlanApprovalEvidence(
                planId = getString("approval_plan_id") ?: return corruptRecord(),
                approvedBy = getString("approval_by") ?: return corruptRecord(),
                evidenceSha256 = getString("approval_sha256") ?: return corruptRecord(),
            ).refine().valueOrNull() ?: return corruptRecord()
            val approved = PersistedAddDeclarationPlan.restoreApproved(
                plan = plan,
                currentVersion = version,
                priorVersion = priorVersion,
                evidence = approval,
            ).valueOrNull() ?: return corruptRecord()
            if (getString("recovery_plan_id") == null) {
                Refinement.Refined(approved)
            } else {
                when (val recovery = decodeRecoveryPrepared(expectedPlanId, approved)) {
                    is Refinement.Refined -> {
                        if (getString("apply_plan_id") == null) {
                            Refinement.Refined(recovery.value)
                        } else {
                            when (val apply = decodeAddDeclarationApply(expectedPlanId, recovery.value)) {
                                is Refinement.Refined -> {
                                    if (getString("verification_plan_id") == null) {
                                        Refinement.Refined(apply.value)
                                    } else {
                                        val applied = apply.value as? AppliedUnverifiedAddDeclaration
                                                      ?: return corruptRecord()
                                        when (val verification = decodeAddDeclarationVerification(applied)) {
                                            is Refinement.Refined -> Refinement.Refined(verification.value)
                                            is Refinement.Rejected -> corruptRecord()
                                        }
                                    }
                                }
                                is Refinement.Rejected -> corruptRecord()
                            }
                        }
                    }
                    is Refinement.Rejected -> corruptRecord()
                }
            }
        }
        else -> corruptRecord()
    }
}

private fun corruptRecord(): Refinement.Rejected<SqliteAddDeclarationPlanRecordDecodeFailure> =
    Refinement.Rejected(SqliteAddDeclarationPlanRecordDecodeFailure.CORRUPT)

private fun <T, F> Refinement<T, F>.valueOrNull(): T? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}
