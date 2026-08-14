package io.github.amichne.kast.change.journal.sqlite

import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanJournalFailure
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanStage
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanStateVersion
import io.github.amichne.kast.change.journal.contract.AppliedUnverifiedAddDeclaration
import io.github.amichne.kast.change.journal.contract.VerifiedAddDeclaration
import io.github.amichne.kast.change.verify.spi.CompleteAddDeclarationVerification
import io.github.amichne.kast.change.verify.spi.CompleteAddDeclarationVerificationResult
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGeneration
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.sql.Connection
import java.sql.ResultSet

internal enum class AddDeclarationVerificationRecordDecodeFailure {
    PLAN_ID_MISMATCH,
    STAGE_INVALID,
    VERSION_INVALID,
    PRIOR_STAGE_INVALID,
    PRIOR_VERSION_INVALID,
    PUBLICATION_GENERATION_INVALID,
    PUBLICATION_IDENTITY_INVALID,
    OBSERVED_IDENTITY_INVALID,
    RECEIPT_MISMATCH,
    LIFECYCLE_INVALID,
}

private enum class VerificationTransactionDisposition {
    NOT_STARTED,
    UNPROVEN,
    ROLLBACK_PROVEN,
}

private sealed interface RequiredVerificationText {
    data class Present(val value: String) : RequiredVerificationText

    data object Missing : RequiredVerificationText
}

/**
 * Proof transition: `CompleteAddDeclarationVerification ->
 * CompleteAddDeclarationVerificationResult`.
 *
 * A completed result establishes one atomic exact v4-to-v5 CAS and durable receipt. Expected
 * failures are closed by [AddDeclarationPlanJournalFailure]. Commit or rollback ambiguity is
 * retained as [CompleteAddDeclarationVerificationResult.CommitOutcomeUnknown]. Raw SQL fields
 * remain inside this SQLite boundary and the connection closes before return.
 */
internal fun SqliteJournalConnections.completeVerification(
    command: CompleteAddDeclarationVerification,
): CompleteAddDeclarationVerificationResult {
    var transactionDisposition = VerificationTransactionDisposition.NOT_STARTED
    return try {
        val result = use { connection ->
            connection.autoCommit = false
            transactionDisposition = VerificationTransactionDisposition.UNPROVEN
            val prior = command.applied
            val verification = command.verification
            val inserted = connection.prepareStatement(
                """INSERT OR IGNORE INTO add_declaration_verification(
                    plan_id, stage, state_version, prior_stage, prior_version,
                    publication_generation, publication_identity,
                    verified_target_path, observed_start_offset, observed_end_offset,
                    observed_package_name, observed_declaration_name, observed_declaration_kind,
                    verified_postimage_sha256
                ) SELECT a.plan_id, 'VERIFIED', ?, 'APPLIED_UNVERIFIED', a.state_version,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?
                FROM add_declaration_apply a
                WHERE a.plan_id = ? AND a.stage = 'APPLIED_UNVERIFIED' AND a.state_version = ?
                    AND a.prior_stage = 'APPLY_ADMITTED' AND a.prior_version = 3
                    AND a.observed_target_path = ? AND a.after_sha256 = ?
                    AND a.after_content_base64 = ?""",
            ).use { statement ->
                statement.setLong(1, command.nextVersion.value)
                statement.setLong(2, verification.publication.generation.value)
                statement.setString(3, verification.publication.identity.value)
                statement.setString(4, verification.identity.targetPath.value)
                statement.setInt(5, verification.identity.sourceRange.startOffset)
                statement.setInt(6, verification.identity.sourceRange.endOffset)
                statement.setString(7, verification.identity.packageName)
                statement.setString(8, verification.identity.declarationName)
                statement.setString(9, verification.identity.declarationKind.name)
                statement.setString(10, prior.afterImage.sha256.value)
                statement.setString(11, prior.plan.planId.value)
                statement.setLong(12, command.expectedVersion.value)
                statement.setString(13, prior.observedWriteSet.paths.single().value)
                statement.setString(14, prior.afterImage.sha256.value)
                statement.setString(15, prior.afterImage.contentBase64)
                statement.executeUpdate()
            }
            val loaded = connection.loadRecord(prior.plan.planId)
            val result = if (inserted == 1) {
                when (loaded) {
                    is SqliteAddDeclarationPlanRecordLoad.Found -> when (val record = loaded.record) {
                        is VerifiedAddDeclaration ->
                            CompleteAddDeclarationVerificationResult.Completed(record)
                        else -> CompleteAddDeclarationVerificationResult.Rejected(
                            AddDeclarationPlanJournalFailure.CorruptRecord,
                        )
                    }
                    SqliteAddDeclarationPlanRecordLoad.Absent,
                    SqliteAddDeclarationPlanRecordLoad.Corrupt,
                    -> CompleteAddDeclarationVerificationResult.Rejected(
                        AddDeclarationPlanJournalFailure.CorruptRecord,
                    )
                }
            } else {
                when (loaded) {
                    SqliteAddDeclarationPlanRecordLoad.Absent ->
                        CompleteAddDeclarationVerificationResult.Rejected(
                            AddDeclarationPlanJournalFailure.PlanNotFound(prior.plan.planId),
                        )
                    SqliteAddDeclarationPlanRecordLoad.Corrupt ->
                        CompleteAddDeclarationVerificationResult.Rejected(
                            AddDeclarationPlanJournalFailure.CorruptRecord,
                        )
                    is SqliteAddDeclarationPlanRecordLoad.Found ->
                        CompleteAddDeclarationVerificationResult.Rejected(
                            AddDeclarationPlanJournalFailure.PriorStateMismatch(
                                planId = prior.plan.planId,
                                expectedStage = AddDeclarationPlanStage.APPLIED_UNVERIFIED,
                                expectedVersion = command.expectedVersion,
                                actualStage = loaded.record.stage,
                                actualVersion = loaded.record.version,
                            ),
                        )
                }
            }
            if (inserted != 1 || result is CompleteAddDeclarationVerificationResult.Rejected) {
                val rollback = rollbackVerification(connection, prior.plan.planId, result)
                transactionDisposition = when (rollback) {
                    is CompleteAddDeclarationVerificationResult.CommitOutcomeUnknown ->
                        VerificationTransactionDisposition.UNPROVEN
                    is CompleteAddDeclarationVerificationResult.Completed,
                    is CompleteAddDeclarationVerificationResult.Rejected,
                    -> VerificationTransactionDisposition.ROLLBACK_PROVEN
                }
                return@use rollback
            }
            try {
                connection.commit()
                observeCommit(SqliteJournalCommitOperation.VERIFICATION_COMPLETION)
                result
            } catch (_: Exception) {
                CompleteAddDeclarationVerificationResult.CommitOutcomeUnknown(prior.plan.planId)
            }
        }
        result
    } catch (_: Exception) {
        when (transactionDisposition) {
            VerificationTransactionDisposition.UNPROVEN ->
                CompleteAddDeclarationVerificationResult.CommitOutcomeUnknown(
                    command.applied.plan.planId,
                )
            VerificationTransactionDisposition.NOT_STARTED,
            VerificationTransactionDisposition.ROLLBACK_PROVEN,
            -> CompleteAddDeclarationVerificationResult.Rejected(
                AddDeclarationPlanJournalFailure.StorageUnavailable,
            )
        }
    }
}

/**
 * Proof transition: stored v5 receipt columns plus exact v4 parent to
 * `Refinement<VerifiedAddDeclaration, AddDeclarationVerificationRecordDecodeFailure>`.
 *
 * Replays the exact terminal transition from the stored full publication and typed observed
 * identity without reconstructing verification obligations. The closed expected failure is
 * [AddDeclarationVerificationRecordDecodeFailure]; raw fields are confined to this decoder.
 */
internal fun ResultSet.decodeAddDeclarationVerification(
    applied: AppliedUnverifiedAddDeclaration,
): Refinement<VerifiedAddDeclaration, AddDeclarationVerificationRecordDecodeFailure> {
    if (getString("verification_plan_id") != applied.plan.planId.value) {
        return rejected(AddDeclarationVerificationRecordDecodeFailure.PLAN_ID_MISMATCH)
    }
    if (getString("verification_stage") != AddDeclarationPlanStage.VERIFIED.name) {
        return rejected(AddDeclarationVerificationRecordDecodeFailure.STAGE_INVALID)
    }
    val currentVersion = when (val parsed = AddDeclarationPlanStateVersion.parse(
        getLong("verification_state_version"),
    )) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected ->
            return rejected(AddDeclarationVerificationRecordDecodeFailure.VERSION_INVALID)
    }
    if (getString("verification_prior_stage") != AddDeclarationPlanStage.APPLIED_UNVERIFIED.name) {
        return rejected(AddDeclarationVerificationRecordDecodeFailure.PRIOR_STAGE_INVALID)
    }
    val priorVersion = when (val parsed = AddDeclarationPlanStateVersion.parse(
        getLong("verification_prior_version"),
    )) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected ->
            return rejected(AddDeclarationVerificationRecordDecodeFailure.PRIOR_VERSION_INVALID)
    }
    val generation = when (val parsed = EvidenceGeneration.parse(
        getLong("verification_publication_generation"),
    )) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> return rejected(
            AddDeclarationVerificationRecordDecodeFailure.PUBLICATION_GENERATION_INVALID,
        )
    }
    val publicationIdentity = when (val text = requiredVerificationText(
        "verification_publication_identity",
    )) {
        is RequiredVerificationText.Present -> text.value
        RequiredVerificationText.Missing -> return rejected(
            AddDeclarationVerificationRecordDecodeFailure.PUBLICATION_IDENTITY_INVALID,
        )
    }
    val identity = when (val parsed = WorkspaceStateIdentity.parse(publicationIdentity)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> return rejected(
            AddDeclarationVerificationRecordDecodeFailure.PUBLICATION_IDENTITY_INVALID,
        )
    }
    if (getString("verification_target_path") != applied.plan.target.targetPath.value ||
        getString("verification_postimage_sha256") != applied.afterImage.sha256.value
    ) {
        return rejected(AddDeclarationVerificationRecordDecodeFailure.RECEIPT_MISMATCH)
    }
    val declarationKind = when (val text = requiredVerificationText(
        "verification_declaration_kind",
    )) {
        is RequiredVerificationText.Present -> text.value
        RequiredVerificationText.Missing -> return rejected(
            AddDeclarationVerificationRecordDecodeFailure.OBSERVED_IDENTITY_INVALID,
        )
    }
    val kind = when (val parsed = decodeDeclarationKind(declarationKind)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> return parsed
    }
    val packageName = when (val text = requiredVerificationText(
        "verification_package_name",
    )) {
        is RequiredVerificationText.Present -> text.value
        RequiredVerificationText.Missing -> return rejected(
            AddDeclarationVerificationRecordDecodeFailure.OBSERVED_IDENTITY_INVALID,
        )
    }
    val declarationName = when (val text = requiredVerificationText(
        "verification_declaration_name",
    )) {
        is RequiredVerificationText.Present -> text.value
        RequiredVerificationText.Missing -> return rejected(
            AddDeclarationVerificationRecordDecodeFailure.OBSERVED_IDENTITY_INVALID,
        )
    }
    return when (val restored = VerifiedAddDeclaration.restore(
        prior = applied,
        currentVersion = currentVersion,
        priorVersion = priorVersion,
        publication = PublishedWorkspaceGeneration(generation, identity),
        targetPath = applied.plan.target.targetPath,
        observedStartOffset = getInt("verification_start_offset"),
        observedEndOffset = getInt("verification_end_offset"),
        observedPackageName = packageName,
        observedDeclarationName = declarationName,
        observedDeclarationKind = kind,
        postimageSha256 = applied.afterImage.sha256,
    )) {
        is Refinement.Refined -> restored
        is Refinement.Rejected -> rejected(
            AddDeclarationVerificationRecordDecodeFailure.LIFECYCLE_INVALID,
        )
    }
}

/**
 * Proof transition: one required SQLite text column to [RequiredVerificationText].
 *
 * Establishes explicit present-or-missing state before raw text reaches a parser. Missing data is
 * mapped by the caller into the closed record-decode failure; raw text remains in this decoder.
 */
private fun ResultSet.requiredVerificationText(column: String): RequiredVerificationText {
    val value = getString(column)
    return if (wasNull()) RequiredVerificationText.Missing else RequiredVerificationText.Present(value)
}

/**
 * Proof transition: stored declaration-kind text to
 * `Refinement<AddDeclarationKind, AddDeclarationVerificationRecordDecodeFailure>`.
 *
 * Establishes one member of the closed declaration-kind vocabulary. The closed expected failure
 * is [AddDeclarationVerificationRecordDecodeFailure.OBSERVED_IDENTITY_INVALID]; raw text may enter
 * only from the verification-row decoder.
 */
private fun decodeDeclarationKind(
    raw: String,
): Refinement<AddDeclarationKind, AddDeclarationVerificationRecordDecodeFailure> = when (raw) {
    AddDeclarationKind.CLASS.name -> Refinement.Refined(AddDeclarationKind.CLASS)
    AddDeclarationKind.INTERFACE.name -> Refinement.Refined(AddDeclarationKind.INTERFACE)
    AddDeclarationKind.OBJECT.name -> Refinement.Refined(AddDeclarationKind.OBJECT)
    AddDeclarationKind.ENUM_CLASS.name -> Refinement.Refined(AddDeclarationKind.ENUM_CLASS)
    AddDeclarationKind.ANNOTATION_CLASS.name -> Refinement.Refined(AddDeclarationKind.ANNOTATION_CLASS)
    AddDeclarationKind.FUNCTION.name -> Refinement.Refined(AddDeclarationKind.FUNCTION)
    AddDeclarationKind.PROPERTY.name -> Refinement.Refined(AddDeclarationKind.PROPERTY)
    AddDeclarationKind.TYPE_ALIAS.name -> Refinement.Refined(AddDeclarationKind.TYPE_ALIAS)
    else -> rejected(AddDeclarationVerificationRecordDecodeFailure.OBSERVED_IDENTITY_INVALID)
}

private fun rejected(
    failure: AddDeclarationVerificationRecordDecodeFailure,
): Refinement.Rejected<AddDeclarationVerificationRecordDecodeFailure> = Refinement.Rejected(failure)

private fun <T : CompleteAddDeclarationVerificationResult> SqliteJournalConnections.rollbackVerification(
    connection: Connection,
    planId: io.github.amichne.kast.change.contract.AddDeclarationPlanId,
    result: T,
): CompleteAddDeclarationVerificationResult = try {
    observeRollback(SqliteJournalCommitOperation.VERIFICATION_COMPLETION)
    connection.rollback()
    result
} catch (_: Exception) {
    CompleteAddDeclarationVerificationResult.CommitOutcomeUnknown(planId)
}
