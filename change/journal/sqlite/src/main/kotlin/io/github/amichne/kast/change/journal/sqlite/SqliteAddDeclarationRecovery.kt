package io.github.amichne.kast.change.journal.sqlite

import io.github.amichne.kast.change.contract.AddDeclarationMutationProgress
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

/**
 * Proof transition:
 * admitted recovery command to a single durable recovery-prepared CAS result.
 *
 * A prepared result establishes an exact persisted before image and one adjacent lifecycle version.
 * Expected journal failures are closed by [AddDeclarationPlanJournalFailure]; the operation closes
 * its SQLite connection before return.
 */
internal fun SqliteJournalConnections.prepareRecovery(
    command: PrepareAddDeclarationRecovery,
): PrepareAddDeclarationRecoveryResult = try {
    use { connection ->
        val planId = command.revalidated.plan.planId
        val nextVersion = command.expectedVersion.next().valueOrNull()
                          ?: return@use PrepareAddDeclarationRecoveryResult.Rejected(
                              AddDeclarationPlanJournalFailure.StateVersionExhausted(planId),
                          )
        val recovery = command.revalidated.recovery
        val updated = connection.prepareStatement(
            """INSERT OR IGNORE INTO add_declaration_recovery(
                plan_id, state_version, prior_stage, prior_version, target_path,
                before_sha256, before_content_base64, mutation_progress
            )
            SELECT p.plan_id, ?, 'APPROVED', p.state_version, ?, ?, ?, 'NOT_BEGUN'
            FROM add_declaration_plan p
            WHERE p.plan_id = ? AND p.stage = 'APPROVED' AND p.state_version = ?""",
        ).use { statement ->
            statement.setLong(1, nextVersion.value)
            statement.setString(2, recovery.targetPath.value)
            statement.setString(3, recovery.beforeImage.sha256.value)
            statement.setString(4, recovery.beforeImage.contentBase64)
            statement.setString(5, planId.value)
            statement.setLong(6, command.expectedVersion.value)
            statement.executeUpdate()
        }
        val actual = connection.loadRecord(planId)
        when {
            updated == 1 -> {
                val prepared = actual as? RecoveryPreparedAddDeclaration
                               ?: return@use PrepareAddDeclarationRecoveryResult.Rejected(
                                   AddDeclarationPlanJournalFailure.CorruptRecord,
                               )
                PrepareAddDeclarationRecoveryResult.Prepared(prepared)
            }
            actual == null -> {
                val failure = if (connection.recordExists(planId)) {
                    AddDeclarationPlanJournalFailure.CorruptRecord
                } else {
                    AddDeclarationPlanJournalFailure.PlanNotFound(planId)
                }
                PrepareAddDeclarationRecoveryResult.Rejected(failure)
            }
            else -> PrepareAddDeclarationRecoveryResult.Rejected(
                AddDeclarationPlanJournalFailure.PriorStateMismatch(
                    planId = planId,
                    expectedStage = AddDeclarationPlanStage.APPROVED,
                    expectedVersion = command.expectedVersion,
                    actualStage = actual.stage,
                    actualVersion = actual.version,
                ),
            )
        }
    }
} catch (_: Exception) {
    PrepareAddDeclarationRecoveryResult.Rejected(
        AddDeclarationPlanJournalFailure.StorageUnavailable,
    )
}

/**
 * Proof transition:
 * stored recovery columns plus their exact approved parent to [RecoveryPreparedAddDeclaration], or
 * `null` for any malformed or mismatched evidence.
 *
 * Establishes canonical byte-exact recovery material, adjacent versions, and mutation not begun.
 * Raw recovery columns are extracted only in this record-decoder boundary.
 */
internal fun ResultSet.toRecoveryPreparedOrNull(
    expectedPlanId: AddDeclarationPlanId,
    approved: PersistedAddDeclarationPlan.Approved,
): RecoveryPreparedAddDeclaration? {
    val recoveryPlanId = AddDeclarationPlanId.parse(
        getString("recovery_plan_id") ?: return null,
    ).valueOrNull() ?: return null
    if (recoveryPlanId != expectedPlanId || recoveryPlanId != approved.plan.planId) return null
    val targetPath = getString("recovery_target_path") ?: return null
    if (targetPath != approved.plan.target.targetPath.value) return null
    val beforeImage = ExactFileContentProof.admit(
        sha256 = getString("recovery_before_sha256") ?: return null,
        contentBase64 = getString("recovery_before_content_base64") ?: return null,
    ).valueOrNull() ?: return null
    val recovery = AddDeclarationRecoveryMaterial.restore(
        plan = approved.plan,
        planId = recoveryPlanId,
        targetPath = approved.plan.target.targetPath,
        beforeImage = beforeImage,
    ).valueOrNull() ?: return null
    val currentVersionRaw = getLong("recovery_state_version")
    if (wasNull()) return null
    val currentVersion = AddDeclarationPlanStateVersion.parse(currentVersionRaw).valueOrNull()
                         ?: return null
    if (getString("recovery_prior_stage") != AddDeclarationPlanStage.APPROVED.name) return null
    val priorVersionRaw = getLong("recovery_prior_version")
    if (wasNull()) return null
    val priorVersion = AddDeclarationPlanStateVersion.parse(priorVersionRaw).valueOrNull()
                       ?: return null
    val progress = runCatching {
        AddDeclarationMutationProgress.valueOf(
            getString("recovery_mutation_progress") ?: return null,
        )
    }.getOrNull() ?: return null
    return RecoveryPreparedAddDeclaration.restore(
        prior = approved,
        currentVersion = currentVersion,
        priorVersion = priorVersion,
        recovery = recovery,
        mutationProgress = progress,
    ).valueOrNull()
}

private fun <T, F> Refinement<T, F>.valueOrNull(): T? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}
