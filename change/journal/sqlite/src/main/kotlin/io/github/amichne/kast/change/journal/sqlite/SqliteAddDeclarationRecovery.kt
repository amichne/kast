package io.github.amichne.kast.change.journal.sqlite

import io.github.amichne.kast.change.contract.AddDeclarationMutationProgress
import io.github.amichne.kast.change.contract.AddDeclarationPlanCodec
import io.github.amichne.kast.change.contract.AddDeclarationPlanId
import io.github.amichne.kast.change.contract.AddDeclarationRecoveryMaterial
import io.github.amichne.kast.change.contract.ExactFileContentProof
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanJournalFailure
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanStage
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanStateVersion
import io.github.amichne.kast.change.journal.contract.PersistedAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.PrepareAddDeclarationRecovery
import io.github.amichne.kast.change.journal.contract.PrepareAddDeclarationRecoveryResult
import io.github.amichne.kast.change.journal.contract.RecoveryPreparedAddDeclaration
import io.github.amichne.kast.kernel.Refinement
import java.sql.ResultSet

internal enum class RecoveryPreparedRecordDecodeFailure {
    PLAN_ID_INVALID,
    PLAN_ID_MISMATCH,
    TARGET_PATH_MISMATCH,
    BEFORE_IMAGE_INVALID,
    RECOVERY_MATERIAL_INVALID,
    CURRENT_VERSION_INVALID,
    PRIOR_STAGE_INVALID,
    PRIOR_VERSION_INVALID,
    MUTATION_PROGRESS_INVALID,
    LIFECYCLE_INVALID,
}

/**
 * Proof transition:
 * `PrepareAddDeclarationRecovery -> PrepareAddDeclarationRecoveryResult`.
 *
 * A prepared result establishes one durable, byte-exact recovery child selected by exact approved
 * stage/version/plan bytes. Expected journal failures are closed by
 * `AddDeclarationPlanJournalFailure`; the operation closes its SQLite connection before return.
 */
internal fun SqliteJournalConnections.prepareRecovery(
    command: PrepareAddDeclarationRecovery,
): PrepareAddDeclarationRecoveryResult = try {
    use { connection ->
        val planId = command.approved.plan.planId
        val nextVersion = command.expectedVersion.next().valueOrNull()
                          ?: return@use PrepareAddDeclarationRecoveryResult.Rejected(
                              AddDeclarationPlanJournalFailure.StateVersionExhausted(planId),
                          )
        connection.autoCommit = false
        val recovery = command.revalidated.recovery
        val updated = connection.prepareStatement(
            """INSERT OR IGNORE INTO add_declaration_recovery(
                plan_id, state_version, prior_stage, prior_version, target_path,
                before_sha256, before_content_base64, mutation_progress
            )
            SELECT p.plan_id, ?, 'APPROVED', p.state_version, ?, ?, ?, 'NOT_BEGUN'
            FROM add_declaration_plan p
            WHERE p.plan_id = ? AND p.stage = 'APPROVED' AND p.state_version = ?
                AND p.plan_bytes = ?""",
        ).use { statement ->
            statement.setLong(1, nextVersion.value)
            statement.setString(2, recovery.targetPath.value)
            statement.setString(3, recovery.beforeImage.sha256.value)
            statement.setString(4, recovery.beforeImage.contentBase64)
            statement.setString(5, planId.value)
            statement.setLong(6, command.expectedVersion.value)
            statement.setString(7, AddDeclarationPlanCodec.encode(command.approved.plan))
            statement.executeUpdate()
        }
        if (updated == 1) {
            observeTransitionWrite(SqliteJournalTransitionOperation.RECOVERY_PREPARATION)
        }
        val loaded = connection.loadRecord(planId)
        val result = if (updated == 1) {
            when (
                val prepared = (loaded as? SqliteAddDeclarationPlanRecordLoad.Found)
                    ?.record as? RecoveryPreparedAddDeclaration
            ) {
                null -> PrepareAddDeclarationRecoveryResult.Rejected(
                    AddDeclarationPlanJournalFailure.CorruptRecord,
                )
                else -> PrepareAddDeclarationRecoveryResult.Prepared(prepared)
            }
        } else {
            when (loaded) {
                is SqliteAddDeclarationPlanRecordLoad.Found ->
                    PrepareAddDeclarationRecoveryResult.Rejected(
                        AddDeclarationPlanJournalFailure.PriorStateMismatch(
                            planId = planId,
                            expectedStage = AddDeclarationPlanStage.APPROVED,
                            expectedVersion = command.expectedVersion,
                            actualStage = loaded.record.stage,
                            actualVersion = loaded.record.version,
                        ),
                    )
                SqliteAddDeclarationPlanRecordLoad.Absent ->
                    PrepareAddDeclarationRecoveryResult.Rejected(
                        AddDeclarationPlanJournalFailure.PlanNotFound(planId),
                    )
                SqliteAddDeclarationPlanRecordLoad.Corrupt ->
                    PrepareAddDeclarationRecoveryResult.Rejected(
                        AddDeclarationPlanJournalFailure.CorruptRecord,
                    )
            }
        }
        if (result is PrepareAddDeclarationRecoveryResult.Prepared) {
            connection.commit()
        } else {
            connection.rollback()
        }
        result
    }
} catch (_: Exception) {
    PrepareAddDeclarationRecoveryResult.Rejected(
        AddDeclarationPlanJournalFailure.StorageUnavailable,
    )
}

/**
 * Proof transition:
 * stored recovery columns plus their exact approved parent to
 * `Refinement<RecoveryPreparedAddDeclaration, RecoveryPreparedRecordDecodeFailure>`.
 *
 * Establishes canonical byte-exact recovery material, adjacent versions, and mutation not begun.
 * The closed expected failure is `RecoveryPreparedRecordDecodeFailure`; raw recovery columns are
 * extracted only in this record-decoder boundary.
 */
internal fun ResultSet.decodeRecoveryPrepared(
    expectedPlanId: AddDeclarationPlanId,
    approved: PersistedAddDeclarationPlan.Approved,
): Refinement<RecoveryPreparedAddDeclaration, RecoveryPreparedRecordDecodeFailure> {
    val rawPlanId = getString("recovery_plan_id")
                       ?: return rejected(RecoveryPreparedRecordDecodeFailure.PLAN_ID_INVALID)
    val recoveryPlanId = when (val parsed = AddDeclarationPlanId.parse(rawPlanId)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected ->
            return rejected(RecoveryPreparedRecordDecodeFailure.PLAN_ID_INVALID)
    }
    if (recoveryPlanId != expectedPlanId || recoveryPlanId != approved.plan.planId) {
        return rejected(RecoveryPreparedRecordDecodeFailure.PLAN_ID_MISMATCH)
    }
    val targetPath = getString("recovery_target_path")
                         ?: return rejected(RecoveryPreparedRecordDecodeFailure.TARGET_PATH_MISMATCH)
    if (targetPath != approved.plan.target.targetPath.value) {
        return rejected(RecoveryPreparedRecordDecodeFailure.TARGET_PATH_MISMATCH)
    }
    val beforeSha256 = getString("recovery_before_sha256")
                           ?: return rejected(RecoveryPreparedRecordDecodeFailure.BEFORE_IMAGE_INVALID)
    val beforeContentBase64 = getString("recovery_before_content_base64")
                                  ?: return rejected(
                                      RecoveryPreparedRecordDecodeFailure.BEFORE_IMAGE_INVALID,
                                  )
    val beforeImage = when (
        val admitted = ExactFileContentProof.admit(
            sha256 = beforeSha256,
            contentBase64 = beforeContentBase64,
        )
    ) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected ->
            return rejected(RecoveryPreparedRecordDecodeFailure.BEFORE_IMAGE_INVALID)
    }
    val recovery = when (val restored = AddDeclarationRecoveryMaterial.restore(
        plan = approved.plan,
        planId = recoveryPlanId,
        targetPath = approved.plan.target.targetPath,
        beforeImage = beforeImage,
    )) {
        is Refinement.Refined -> restored.value
        is Refinement.Rejected ->
            return rejected(RecoveryPreparedRecordDecodeFailure.RECOVERY_MATERIAL_INVALID)
    }
    val currentVersionRaw = getLong("recovery_state_version")
    if (wasNull()) return rejected(RecoveryPreparedRecordDecodeFailure.CURRENT_VERSION_INVALID)
    val currentVersion = when (val parsed = AddDeclarationPlanStateVersion.parse(currentVersionRaw)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected ->
            return rejected(RecoveryPreparedRecordDecodeFailure.CURRENT_VERSION_INVALID)
    }
    if (getString("recovery_prior_stage") != AddDeclarationPlanStage.APPROVED.name) {
        return rejected(RecoveryPreparedRecordDecodeFailure.PRIOR_STAGE_INVALID)
    }
    val priorVersionRaw = getLong("recovery_prior_version")
    if (wasNull()) return rejected(RecoveryPreparedRecordDecodeFailure.PRIOR_VERSION_INVALID)
    val priorVersion = when (val parsed = AddDeclarationPlanStateVersion.parse(priorVersionRaw)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected ->
            return rejected(RecoveryPreparedRecordDecodeFailure.PRIOR_VERSION_INVALID)
    }
    val rawProgress = getString("recovery_mutation_progress")
                          ?: return rejected(
                              RecoveryPreparedRecordDecodeFailure.MUTATION_PROGRESS_INVALID,
                          )
    val progress = AddDeclarationMutationProgress.entries.singleOrNull { it.name == rawProgress }
                       ?: return rejected(
                           RecoveryPreparedRecordDecodeFailure.MUTATION_PROGRESS_INVALID,
                       )
    return when (val restored = RecoveryPreparedAddDeclaration.restore(
        prior = approved,
        currentVersion = currentVersion,
        priorVersion = priorVersion,
        recovery = recovery,
        mutationProgress = progress,
    )) {
        is Refinement.Refined -> restored
        is Refinement.Rejected -> rejected(RecoveryPreparedRecordDecodeFailure.LIFECYCLE_INVALID)
    }
}

private fun <T, F> Refinement<T, F>.valueOrNull(): T? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}

private fun rejected(
    failure: RecoveryPreparedRecordDecodeFailure,
): Refinement.Rejected<RecoveryPreparedRecordDecodeFailure> = Refinement.Rejected(failure)
