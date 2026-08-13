package io.github.amichne.kast.change.journal.sqlite

import io.github.amichne.kast.change.contract.AddDeclarationMutationProgress
import io.github.amichne.kast.change.contract.AddDeclarationPlanCodec
import io.github.amichne.kast.change.contract.AddDeclarationPlanId
import io.github.amichne.kast.change.contract.DeclaredWriteSet
import io.github.amichne.kast.change.contract.ExactFileContentProof
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanJournalFailure
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanStage
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanStateVersion
import io.github.amichne.kast.change.journal.contract.AppliedUnverifiedAddDeclaration
import io.github.amichne.kast.change.journal.contract.ApplyAdmittedAddDeclaration
import io.github.amichne.kast.change.journal.contract.BeginAddDeclarationApply
import io.github.amichne.kast.change.journal.contract.BeginAddDeclarationApplyResult
import io.github.amichne.kast.change.journal.contract.CompleteAddDeclarationApply
import io.github.amichne.kast.change.journal.contract.CompleteAddDeclarationApplyResult
import io.github.amichne.kast.change.journal.contract.PersistedAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.RecoveryPreparedAddDeclaration
import io.github.amichne.kast.kernel.Refinement
import java.sql.ResultSet

internal enum class AddDeclarationApplyRecordDecodeFailure {
    PLAN_ID_INVALID,
    PLAN_ID_MISMATCH,
    STAGE_INVALID,
    VERSION_INVALID,
    PRIOR_STAGE_INVALID,
    PRIOR_VERSION_INVALID,
    POSTIMAGE_INVALID,
    WRITE_SET_INVALID,
    LIFECYCLE_INVALID,
}

/**
 * Proof transition: `BeginAddDeclarationApply -> BeginAddDeclarationApplyResult`.
 *
 * A begun result establishes an exact recovery-prepared-to-apply-admitted CAS before source I/O.
 * Expected failures are closed by `AddDeclarationPlanJournalFailure`; raw SQL fields are confined
 * to this SQLite boundary and the connection closes before return.
 */
internal fun SqliteJournalConnections.beginApply(
    command: BeginAddDeclarationApply,
): BeginAddDeclarationApplyResult {
    var commitAttempted = false
    return try {
        use { connection ->
            connection.autoCommit = false
            val prior = command.recoveryPrepared
            val planId = prior.plan.planId
            val next = command.nextVersion
            val inserted = connection.prepareStatement(
                """INSERT OR IGNORE INTO add_declaration_apply(
                    plan_id, stage, state_version, prior_stage, prior_version
                ) SELECT r.plan_id, 'APPLY_ADMITTED', ?, 'RECOVERY_PREPARED', r.state_version
                FROM add_declaration_recovery r
                JOIN add_declaration_plan p ON p.plan_id = r.plan_id
                WHERE r.plan_id = ? AND r.state_version = ? AND r.prior_stage = 'APPROVED'
                    AND r.prior_version = 1 AND r.target_path = ? AND r.before_sha256 = ?
                    AND r.before_content_base64 = ? AND r.mutation_progress = 'NOT_BEGUN'
                    AND p.plan_bytes = ?""",
            ).use { statement ->
                statement.setLong(1, next.value)
                statement.setString(2, planId.value)
                statement.setLong(3, prior.version.value)
                statement.setString(4, prior.recovery.targetPath.value)
                statement.setString(5, prior.recovery.beforeImage.sha256.value)
                statement.setString(6, prior.recovery.beforeImage.contentBase64)
                statement.setString(7, AddDeclarationPlanCodec.encode(prior.plan))
                statement.executeUpdate()
            }
            val loaded = connection.loadRecord(planId)
            val result = if (inserted == 1) {
                BeginAddDeclarationApplyResult.Begun(
                    (loaded as? SqliteAddDeclarationPlanRecordLoad.Found)
                        ?.record as? ApplyAdmittedAddDeclaration
                    ?: return@use rollback(
                        connection,
                        BeginAddDeclarationApplyResult.Rejected(
                            AddDeclarationPlanJournalFailure.CorruptRecord,
                        ),
                    ),
                )
            } else {
                when (loaded) {
                    SqliteAddDeclarationPlanRecordLoad.Absent ->
                        BeginAddDeclarationApplyResult.Rejected(
                            AddDeclarationPlanJournalFailure.PlanNotFound(planId),
                        )
                    SqliteAddDeclarationPlanRecordLoad.Corrupt ->
                        BeginAddDeclarationApplyResult.Rejected(
                            AddDeclarationPlanJournalFailure.CorruptRecord,
                        )
                    is SqliteAddDeclarationPlanRecordLoad.Found ->
                        BeginAddDeclarationApplyResult.Rejected(
                            AddDeclarationPlanJournalFailure.PriorStateMismatch(
                                planId = planId,
                                expectedStage = AddDeclarationPlanStage.RECOVERY_PREPARED,
                                expectedVersion = prior.version,
                                actualStage = loaded.record.stage,
                                actualVersion = loaded.record.version,
                            ),
                        )
                }
            }
            if (inserted != 1) return@use rollback(connection, result)
            try {
                commitAttempted = true
                connection.commit()
                observeCommit(SqliteJournalCommitOperation.APPLY_ADMISSION)
                result
            } catch (_: Exception) {
                BeginAddDeclarationApplyResult.CommitOutcomeUnknown(planId)
            }
        }
    } catch (_: Exception) {
        if (commitAttempted) {
            BeginAddDeclarationApplyResult.CommitOutcomeUnknown(command.recoveryPrepared.plan.planId)
        } else {
            BeginAddDeclarationApplyResult.Rejected(
                AddDeclarationPlanJournalFailure.StorageUnavailable,
            )
        }
    }
}

/**
 * Proof transition: `CompleteAddDeclarationApply -> CompleteAddDeclarationApplyResult`.
 *
 * A completed result establishes an exact apply-admitted-to-applied-unverified CAS after physical
 * closure. Expected failures are closed by `AddDeclarationPlanJournalFailure`; raw SQL fields are
 * confined to this SQLite boundary and the connection closes before return.
 */
internal fun SqliteJournalConnections.completeApply(
    command: CompleteAddDeclarationApply,
): CompleteAddDeclarationApplyResult {
    var commitAttempted = false
    return try {
        use { connection ->
            connection.autoCommit = false
            val prior = command.admitted
            val planId = prior.plan.planId
            val next = command.nextVersion
            val updated = connection.prepareStatement(
                """UPDATE add_declaration_apply SET
                    stage = 'APPLIED_UNVERIFIED', state_version = ?,
                    prior_stage = 'APPLY_ADMITTED', prior_version = ?,
                    observed_target_path = ?, after_sha256 = ?, after_content_base64 = ?
                WHERE plan_id = ? AND stage = 'APPLY_ADMITTED' AND state_version = ?
                    AND prior_stage = 'RECOVERY_PREPARED' AND prior_version = 2
                    AND observed_target_path IS NULL AND after_sha256 IS NULL
                    AND after_content_base64 IS NULL""",
            ).use { statement ->
                statement.setLong(1, next.value)
                statement.setLong(2, prior.version.value)
                statement.setString(3, command.observedWriteSet.paths.single().value)
                statement.setString(4, command.afterImage.sha256.value)
                statement.setString(5, command.afterImage.contentBase64)
                statement.setString(6, planId.value)
                statement.setLong(7, prior.version.value)
                statement.executeUpdate()
            }
            val loaded = connection.loadRecord(planId)
            val result = if (updated == 1) {
                CompleteAddDeclarationApplyResult.Completed(
                    (loaded as? SqliteAddDeclarationPlanRecordLoad.Found)
                        ?.record as? AppliedUnverifiedAddDeclaration
                    ?: return@use rollback(
                        connection,
                        CompleteAddDeclarationApplyResult.Rejected(
                            AddDeclarationPlanJournalFailure.CorruptRecord,
                        ),
                    ),
                )
            } else {
                when (loaded) {
                    SqliteAddDeclarationPlanRecordLoad.Absent ->
                        CompleteAddDeclarationApplyResult.Rejected(
                            AddDeclarationPlanJournalFailure.PlanNotFound(planId),
                        )
                    SqliteAddDeclarationPlanRecordLoad.Corrupt ->
                        CompleteAddDeclarationApplyResult.Rejected(
                            AddDeclarationPlanJournalFailure.CorruptRecord,
                        )
                    is SqliteAddDeclarationPlanRecordLoad.Found ->
                        CompleteAddDeclarationApplyResult.Rejected(
                            AddDeclarationPlanJournalFailure.PriorStateMismatch(
                                planId = planId,
                                expectedStage = AddDeclarationPlanStage.APPLY_ADMITTED,
                                expectedVersion = prior.version,
                                actualStage = loaded.record.stage,
                                actualVersion = loaded.record.version,
                            ),
                        )
                }
            }
            if (updated != 1) return@use rollback(connection, result)
            try {
                commitAttempted = true
                connection.commit()
                observeCommit(SqliteJournalCommitOperation.APPLY_COMPLETION)
                result
            } catch (_: Exception) {
                CompleteAddDeclarationApplyResult.CommitOutcomeUnknown(planId)
            }
        }
    } catch (_: Exception) {
        if (commitAttempted) {
            CompleteAddDeclarationApplyResult.CommitOutcomeUnknown(command.admitted.plan.planId)
        } else {
            CompleteAddDeclarationApplyResult.Rejected(
                AddDeclarationPlanJournalFailure.StorageUnavailable,
            )
        }
    }
}

/**
 * Proof transition:
 * stored apply columns plus exact recovery-prepared parent to
 * `Refinement<PersistedAddDeclarationPlan, AddDeclarationApplyRecordDecodeFailure>`.
 *
 * Replays v3 or v4 without trusting stored derived state. The closed expected failure is
 * `AddDeclarationApplyRecordDecodeFailure`; raw SQLite values are confined to this decoder.
 */
internal fun ResultSet.decodeAddDeclarationApply(
    expectedPlanId: AddDeclarationPlanId,
    recoveryPrepared: RecoveryPreparedAddDeclaration,
): Refinement<PersistedAddDeclarationPlan, AddDeclarationApplyRecordDecodeFailure> {
    val rawPlanId = getString("apply_plan_id")
                    ?: return rejected(AddDeclarationApplyRecordDecodeFailure.PLAN_ID_INVALID)
    val planId = AddDeclarationPlanId.parse(rawPlanId).valueOrNull()
                 ?: return rejected(AddDeclarationApplyRecordDecodeFailure.PLAN_ID_INVALID)
    if (planId != expectedPlanId || planId != recoveryPrepared.plan.planId) {
        return rejected(AddDeclarationApplyRecordDecodeFailure.PLAN_ID_MISMATCH)
    }
    val stage = AddDeclarationPlanStage.entries.singleOrNull {
        it.name == getString("apply_stage")
    } ?: return rejected(AddDeclarationApplyRecordDecodeFailure.STAGE_INVALID)
    val version = AddDeclarationPlanStateVersion.parse(getLong("apply_state_version")).valueOrNull()
                  ?: return rejected(AddDeclarationApplyRecordDecodeFailure.VERSION_INVALID)
    val priorStage = AddDeclarationPlanStage.entries.singleOrNull {
        it.name == getString("apply_prior_stage")
    } ?: return rejected(AddDeclarationApplyRecordDecodeFailure.PRIOR_STAGE_INVALID)
    val priorVersion = AddDeclarationPlanStateVersion.parse(getLong("apply_prior_version")).valueOrNull()
                       ?: return rejected(AddDeclarationApplyRecordDecodeFailure.PRIOR_VERSION_INVALID)
    val admittedVersion = recoveryPrepared.version.next().valueOrNull()
                          ?: return rejected(AddDeclarationApplyRecordDecodeFailure.VERSION_INVALID)
    val admitted = ApplyAdmittedAddDeclaration.restore(
        prior = recoveryPrepared,
        currentVersion = admittedVersion,
        priorVersion = recoveryPrepared.version,
        mutationProgress = AddDeclarationMutationProgress.MAY_HAVE_BEGUN,
    ).valueOrNull() ?: return rejected(AddDeclarationApplyRecordDecodeFailure.LIFECYCLE_INVALID)
    if (stage == AddDeclarationPlanStage.APPLY_ADMITTED) {
        return if (
            version == admitted.version &&
            priorStage == AddDeclarationPlanStage.RECOVERY_PREPARED &&
            priorVersion == recoveryPrepared.version &&
            getString("apply_observed_target_path") == null &&
            getString("apply_after_sha256") == null &&
            getString("apply_after_content_base64") == null
        ) {
            Refinement.Refined(admitted)
        } else {
            rejected(AddDeclarationApplyRecordDecodeFailure.LIFECYCLE_INVALID)
        }
    }
    if (stage != AddDeclarationPlanStage.APPLIED_UNVERIFIED) {
        return rejected(AddDeclarationApplyRecordDecodeFailure.STAGE_INVALID)
    }
    val observedPath = getString("apply_observed_target_path")
                       ?: return rejected(AddDeclarationApplyRecordDecodeFailure.WRITE_SET_INVALID)
    if (observedPath != recoveryPrepared.plan.target.targetPath.value) {
        return rejected(AddDeclarationApplyRecordDecodeFailure.WRITE_SET_INVALID)
    }
    val observedWriteSet = DeclaredWriteSet.admit(
        listOf(recoveryPrepared.plan.target.targetPath),
    ).valueOrNull() ?: return rejected(AddDeclarationApplyRecordDecodeFailure.WRITE_SET_INVALID)
    val afterSha256 = getString("apply_after_sha256")
                      ?: return rejected(AddDeclarationApplyRecordDecodeFailure.POSTIMAGE_INVALID)
    val afterContent = getString("apply_after_content_base64")
                       ?: return rejected(AddDeclarationApplyRecordDecodeFailure.POSTIMAGE_INVALID)
    val afterImage = ExactFileContentProof.admit(afterSha256, afterContent).valueOrNull()
                     ?: return rejected(AddDeclarationApplyRecordDecodeFailure.POSTIMAGE_INVALID)
    return when (val restored = AppliedUnverifiedAddDeclaration.restore(
        prior = admitted,
        currentVersion = version,
        priorVersion = priorVersion,
        afterImage = afterImage,
        observedWriteSet = observedWriteSet,
        mutationProgress = AddDeclarationMutationProgress.BEGUN,
    )) {
        is Refinement.Refined -> {
            if (priorStage == AddDeclarationPlanStage.APPLY_ADMITTED) restored
            else rejected(AddDeclarationApplyRecordDecodeFailure.PRIOR_STAGE_INVALID)
        }
        is Refinement.Rejected -> rejected(AddDeclarationApplyRecordDecodeFailure.LIFECYCLE_INVALID)
    }
}

private fun <T, F> Refinement<T, F>.valueOrNull(): T? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}

private fun <T> rollback(connection: java.sql.Connection, result: T): T {
    runCatching(connection::rollback)
    return result
}

private fun rejected(
    failure: AddDeclarationApplyRecordDecodeFailure,
): Refinement.Rejected<AddDeclarationApplyRecordDecodeFailure> = Refinement.Rejected(failure)
