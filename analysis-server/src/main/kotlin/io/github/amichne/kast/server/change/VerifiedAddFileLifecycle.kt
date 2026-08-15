package io.github.amichne.kast.server.change

import io.github.amichne.kast.api.contract.result.AdditionPostimageSha256
import io.github.amichne.kast.api.contract.result.AdditionTargetPath
import io.github.amichne.kast.api.contract.result.AdditionKotlinPackage
import io.github.amichne.kast.api.contract.result.AdditionTopLevelDeclaration
import io.github.amichne.kast.api.contract.result.MutationSemanticGeneration

data class VerifiedAddFileReceipt(
    val targetPath: AdditionTargetPath,
    val postimageSha256: AdditionPostimageSha256,
    val generation: MutationSemanticGeneration,
    val packageIdentity: AdditionKotlinPackage,
    val declarations: List<AdditionTopLevelDeclaration>,
)
enum class VerifiedAddFilePlanStage {
    AWAITING_APPROVAL,
    APPROVED,
    RECOVERY_PREPARED,
    APPLY_ADMITTED,
    APPLIED_UNVERIFIED,
}

data class VerifiedAddFilePlanPreview(
    val targetPath: VerifiedAddFileTargetPath,
    val proposedContent: VerifiedAddFileContent,
    val generation: MutationSemanticGeneration,
)

sealed interface VerifiedAddFilePlanResult {
    data class Planned(
        val planId: VerifiedAddFilePlanId,
        val planVersion: VerifiedAddFilePlanVersion,
        val preview: VerifiedAddFilePlanPreview,
    ) : VerifiedAddFilePlanResult {
        val stage: VerifiedAddFilePlanStage = VerifiedAddFilePlanStage.AWAITING_APPROVAL
    }

    data class Rejected(
        val failure: VerifiedAddFileFailure,
    ) : VerifiedAddFilePlanResult
}

enum class VerifiedAddFileRecoveryDispositionAction {
    DELETE_CREATED_TARGET,
}

enum class VerifiedAddFileReconciliationAction {
    INSPECT_TARGET,
}

sealed interface VerifiedAddFileRecoveryDisposition {
    data object RolledBack : VerifiedAddFileRecoveryDisposition
    data object Cancelled : VerifiedAddFileRecoveryDisposition
    data class RecoveryRequired(
        val action: VerifiedAddFileRecoveryDispositionAction,
    ) : VerifiedAddFileRecoveryDisposition
    data class ReconciliationRequired(
        val action: VerifiedAddFileReconciliationAction,
    ) : VerifiedAddFileRecoveryDisposition
}

enum class VerifiedAddFileApplyOutcome {
    VERIFIED,
    REJECTED,
    ROLLED_BACK,
    RECOVERY_REQUIRED,
    RECONCILIATION_REQUIRED,
}

sealed interface VerifiedAddFileApplyResult {
    data class Verified(
        val planId: VerifiedAddFilePlanId,
        val planVersion: VerifiedAddFilePlanVersion,
        val receipt: VerifiedAddFileReceipt,
    ) : VerifiedAddFileApplyResult {
        val outcome: VerifiedAddFileApplyOutcome = VerifiedAddFileApplyOutcome.VERIFIED
    }

    data class Rejected(
        val planId: VerifiedAddFilePlanId,
        val planVersion: VerifiedAddFilePlanVersion,
        val stage: VerifiedAddFilePlanStage,
        val progress: VerifiedAddFileProgress,
        val failure: VerifiedAddFileFailure,
    ) : VerifiedAddFileApplyResult {
        val outcome: VerifiedAddFileApplyOutcome = VerifiedAddFileApplyOutcome.REJECTED
    }

    data class RolledBack(
        val planId: VerifiedAddFilePlanId,
        val planVersion: VerifiedAddFilePlanVersion,
        val stage: VerifiedAddFilePlanStage,
        val progress: VerifiedAddFileProgress,
        val failure: VerifiedAddFileFailure,
        val action: VerifiedAddFileRecoveryDispositionAction,
    ) : VerifiedAddFileApplyResult {
        val outcome: VerifiedAddFileApplyOutcome = VerifiedAddFileApplyOutcome.ROLLED_BACK
    }

    data class RecoveryRequired(
        val planId: VerifiedAddFilePlanId,
        val recoveryId: VerifiedAddFileRecoveryId,
        val planVersion: VerifiedAddFilePlanVersion,
        val stage: VerifiedAddFilePlanStage,
        val progress: VerifiedAddFileProgress,
        val failure: VerifiedAddFileFailure,
        val action: VerifiedAddFileRecoveryDispositionAction,
    ) : VerifiedAddFileApplyResult {
        val outcome: VerifiedAddFileApplyOutcome = VerifiedAddFileApplyOutcome.RECOVERY_REQUIRED
    }

    data class ReconciliationRequired(
        val planId: VerifiedAddFilePlanId,
        val recoveryId: VerifiedAddFileRecoveryId,
        val planVersion: VerifiedAddFilePlanVersion,
        val stage: VerifiedAddFilePlanStage,
        val progress: VerifiedAddFileProgress,
        val failure: VerifiedAddFileFailure,
        val action: VerifiedAddFileReconciliationAction,
    ) : VerifiedAddFileApplyResult {
        val outcome: VerifiedAddFileApplyOutcome = VerifiedAddFileApplyOutcome.RECONCILIATION_REQUIRED
    }
}

enum class VerifiedAddFileApplyResultFailure {
    INCOMPATIBLE_LIFECYCLE,
}

sealed interface VerifiedAddFileApplyResultAdmission {
    data class Admitted(
        val value: AdmittedVerifiedAddFileApplyResult,
    ) : VerifiedAddFileApplyResultAdmission

    data class Rejected(
        val failure: VerifiedAddFileApplyResultFailure,
    ) : VerifiedAddFileApplyResultAdmission
}

private sealed interface VerifiedAddFileLifecycleCompatibility {
    data class Compatible(
        val result: VerifiedAddFileApplyResult,
    ) : VerifiedAddFileLifecycleCompatibility

    data object Incompatible : VerifiedAddFileLifecycleCompatibility
}

/**
 * A public add-file result whose stage, progress, failure, recovery identity, and version agree.
 *
 * Raw result extraction is permitted only at the JSON-RPC serialization boundary.
 */
class AdmittedVerifiedAddFileApplyResult private constructor(
    val result: VerifiedAddFileApplyResult,
) {
    companion object {
        /**
         * Proof transition:
         * `VerifiedAddFileApplyResult -> VerifiedAddFileApplyResultAdmission`.
         *
         * Establishes the closed add-file lifecycle matrix and the required v0/v5 and recovery-id
         * relationships. The closed expected failure is
         * [VerifiedAddFileApplyResultFailure.INCOMPATIBLE_LIFECYCLE].
         */
        fun admit(
            candidate: VerifiedAddFileApplyResult,
        ): VerifiedAddFileApplyResultAdmission =
            when (val compatibility = classifyVerifiedAddFileLifecycle(candidate)) {
                is VerifiedAddFileLifecycleCompatibility.Compatible ->
                    VerifiedAddFileApplyResultAdmission.Admitted(
                        AdmittedVerifiedAddFileApplyResult(compatibility.result),
                    )
                VerifiedAddFileLifecycleCompatibility.Incompatible ->
                    VerifiedAddFileApplyResultAdmission.Rejected(
                        VerifiedAddFileApplyResultFailure.INCOMPATIBLE_LIFECYCLE,
                    )
            }
    }
}

/**
 * Proof transition:
 * `VerifiedAddFileApplyResult -> VerifiedAddFileLifecycleCompatibility`.
 *
 * Establishes the candidate's exact version and, where present, recovery-identity relationship
 * before delegating stage/progress/failure admission to the outcome-specific transition. The
 * closed failure is [VerifiedAddFileLifecycleCompatibility.Incompatible]. Raw result extraction
 * remains restricted to the JSON-RPC serialization boundary after public admission.
 */
private fun classifyVerifiedAddFileLifecycle(
    candidate: VerifiedAddFileApplyResult,
): VerifiedAddFileLifecycleCompatibility = when (candidate) {
    is VerifiedAddFileApplyResult.Verified -> when {
        candidate.planVersion.value != TERMINAL_VERSION ->
            VerifiedAddFileLifecycleCompatibility.Incompatible
        candidate.receipt.declarations.isEmpty() ->
            VerifiedAddFileLifecycleCompatibility.Incompatible
        else -> VerifiedAddFileLifecycleCompatibility.Compatible(candidate)
    }
    is VerifiedAddFileApplyResult.Rejected -> when {
        candidate.planVersion.value != INITIAL_VERSION ->
            VerifiedAddFileLifecycleCompatibility.Incompatible
        else -> classifyRejectedLifecycle(candidate)
    }
    is VerifiedAddFileApplyResult.RolledBack -> when {
        candidate.planVersion.value != TERMINAL_VERSION ->
            VerifiedAddFileLifecycleCompatibility.Incompatible
        else -> classifyRecoveryLifecycle(
            result = candidate,
            stage = candidate.stage,
            progress = candidate.progress,
            failure = candidate.failure,
        )
    }
    is VerifiedAddFileApplyResult.RecoveryRequired -> when {
        candidate.planVersion.value != INITIAL_VERSION ->
            VerifiedAddFileLifecycleCompatibility.Incompatible
        candidate.recoveryId.value != candidate.planId.value ->
            VerifiedAddFileLifecycleCompatibility.Incompatible
        else -> classifyRecoveryLifecycle(
            result = candidate,
            stage = candidate.stage,
            progress = candidate.progress,
            failure = candidate.failure,
        )
    }
    is VerifiedAddFileApplyResult.ReconciliationRequired -> when {
        candidate.planVersion.value != INITIAL_VERSION ->
            VerifiedAddFileLifecycleCompatibility.Incompatible
        candidate.recoveryId.value != candidate.planId.value ->
            VerifiedAddFileLifecycleCompatibility.Incompatible
        else -> classifyRecoveryLifecycle(
            result = candidate,
            stage = candidate.stage,
            progress = candidate.progress,
            failure = candidate.failure,
        )
    }
}

/**
 * Proof transition:
 * `VerifiedAddFileApplyResult.Rejected -> VerifiedAddFileLifecycleCompatibility`.
 *
 * Establishes the exact rejected stage/progress/failure matrix. The closed failure is
 * [VerifiedAddFileLifecycleCompatibility.Incompatible].
 */
private fun classifyRejectedLifecycle(
    candidate: VerifiedAddFileApplyResult.Rejected,
): VerifiedAddFileLifecycleCompatibility {
    val expectedStage = when (candidate.progress) {
        VerifiedAddFileProgress.INTENT_ADMISSION,
        VerifiedAddFileProgress.PLANNING,
        -> VerifiedAddFilePlanStage.AWAITING_APPROVAL
        VerifiedAddFileProgress.REVALIDATION -> VerifiedAddFilePlanStage.APPROVED
        VerifiedAddFileProgress.RECOVERY_PREPARATION -> VerifiedAddFilePlanStage.RECOVERY_PREPARED
        VerifiedAddFileProgress.SOURCE_APPLICATION -> VerifiedAddFilePlanStage.APPLY_ADMITTED
        VerifiedAddFileProgress.WORKSPACE_PUBLICATION,
        VerifiedAddFileProgress.PSI_ADMISSION,
        -> return VerifiedAddFileLifecycleCompatibility.Incompatible
    }
    if (candidate.stage != expectedStage) {
        return VerifiedAddFileLifecycleCompatibility.Incompatible
    }
    return when (candidate.progress) {
        VerifiedAddFileProgress.INTENT_ADMISSION -> when (candidate.failure) {
            VerifiedAddFileFailure.WORKSPACE_MISMATCH,
            VerifiedAddFileFailure.PLAN_NOT_FOUND,
            -> VerifiedAddFileLifecycleCompatibility.Compatible(candidate)
            else -> VerifiedAddFileLifecycleCompatibility.Incompatible
        }
        VerifiedAddFileProgress.PLANNING -> when (candidate.failure) {
            VerifiedAddFileFailure.TARGET_ALREADY_EXISTS,
            VerifiedAddFileFailure.TARGET_GENERATED,
            VerifiedAddFileFailure.TARGET_AMBIGUOUSLY_OWNED,
            VerifiedAddFileFailure.TARGET_SYMLINK_ESCAPE,
            VerifiedAddFileFailure.PACKAGE_OR_DECLARATION_INVALID,
            VerifiedAddFileFailure.CANCELLED,
            -> VerifiedAddFileLifecycleCompatibility.Compatible(candidate)
            else -> VerifiedAddFileLifecycleCompatibility.Incompatible
        }
        VerifiedAddFileProgress.REVALIDATION -> when (candidate.failure) {
            VerifiedAddFileFailure.TARGET_ALREADY_EXISTS,
            VerifiedAddFileFailure.TARGET_GENERATED,
            VerifiedAddFileFailure.TARGET_AMBIGUOUSLY_OWNED,
            VerifiedAddFileFailure.TARGET_SYMLINK_ESCAPE,
            VerifiedAddFileFailure.PACKAGE_OR_DECLARATION_INVALID,
            VerifiedAddFileFailure.STALE_PLAN_VERSION,
            VerifiedAddFileFailure.APPROVAL_REJECTED,
            VerifiedAddFileFailure.PLAN_REVALIDATION_FAILED,
            VerifiedAddFileFailure.CANCELLED,
            -> VerifiedAddFileLifecycleCompatibility.Compatible(candidate)
            else -> VerifiedAddFileLifecycleCompatibility.Incompatible
        }
        VerifiedAddFileProgress.RECOVERY_PREPARATION -> when (candidate.failure) {
            VerifiedAddFileFailure.TARGET_ALREADY_EXISTS,
            VerifiedAddFileFailure.TARGET_NOT_WRITABLE,
            VerifiedAddFileFailure.TARGET_SYMLINK_ESCAPE,
            VerifiedAddFileFailure.PLAN_NOT_FOUND,
            -> VerifiedAddFileLifecycleCompatibility.Compatible(candidate)
            else -> VerifiedAddFileLifecycleCompatibility.Incompatible
        }
        VerifiedAddFileProgress.SOURCE_APPLICATION -> when (candidate.failure) {
            VerifiedAddFileFailure.VCS_WRITE_PROMPT_REJECTED ->
                VerifiedAddFileLifecycleCompatibility.Compatible(candidate)
            else -> VerifiedAddFileLifecycleCompatibility.Incompatible
        }
        VerifiedAddFileProgress.WORKSPACE_PUBLICATION,
        VerifiedAddFileProgress.PSI_ADMISSION,
        -> VerifiedAddFileLifecycleCompatibility.Incompatible
    }
}

/**
 * Proof transition: a recovery-capable add-file result plus its stage/progress/failure evidence
 * becomes [VerifiedAddFileLifecycleCompatibility.Compatible].
 *
 * Establishes the exact post-prepare stage/progress/failure matrix. The closed failure is
 * [VerifiedAddFileLifecycleCompatibility.Incompatible].
 */
private fun classifyRecoveryLifecycle(
    result: VerifiedAddFileApplyResult,
    stage: VerifiedAddFilePlanStage,
    progress: VerifiedAddFileProgress,
    failure: VerifiedAddFileFailure,
): VerifiedAddFileLifecycleCompatibility {
    val expectedStage = when (progress) {
        VerifiedAddFileProgress.SOURCE_APPLICATION -> VerifiedAddFilePlanStage.APPLY_ADMITTED
        VerifiedAddFileProgress.WORKSPACE_PUBLICATION,
        VerifiedAddFileProgress.PSI_ADMISSION,
        -> VerifiedAddFilePlanStage.APPLIED_UNVERIFIED
        VerifiedAddFileProgress.INTENT_ADMISSION,
        VerifiedAddFileProgress.PLANNING,
        VerifiedAddFileProgress.REVALIDATION,
        VerifiedAddFileProgress.RECOVERY_PREPARATION,
        -> return VerifiedAddFileLifecycleCompatibility.Incompatible
    }
    if (stage != expectedStage) {
        return VerifiedAddFileLifecycleCompatibility.Incompatible
    }
    return when (progress) {
        VerifiedAddFileProgress.SOURCE_APPLICATION -> when (failure) {
            VerifiedAddFileFailure.SOURCE_APPLICATION_FAILED,
            VerifiedAddFileFailure.CANCELLED,
            -> VerifiedAddFileLifecycleCompatibility.Compatible(result)
            else -> VerifiedAddFileLifecycleCompatibility.Incompatible
        }
        VerifiedAddFileProgress.WORKSPACE_PUBLICATION -> when (failure) {
            VerifiedAddFileFailure.PUBLICATION_FAILED,
            VerifiedAddFileFailure.CANCELLED,
            -> VerifiedAddFileLifecycleCompatibility.Compatible(result)
            else -> VerifiedAddFileLifecycleCompatibility.Incompatible
        }
        VerifiedAddFileProgress.PSI_ADMISSION -> when (failure) {
            VerifiedAddFileFailure.PSI_NOT_ADMITTED,
            VerifiedAddFileFailure.GENERATION_NOT_ADVANCED,
            VerifiedAddFileFailure.CANCELLED,
            -> VerifiedAddFileLifecycleCompatibility.Compatible(result)
            else -> VerifiedAddFileLifecycleCompatibility.Incompatible
        }
        VerifiedAddFileProgress.INTENT_ADMISSION,
        VerifiedAddFileProgress.PLANNING,
        VerifiedAddFileProgress.REVALIDATION,
        VerifiedAddFileProgress.RECOVERY_PREPARATION,
        -> VerifiedAddFileLifecycleCompatibility.Incompatible
    }
}

enum class VerifiedAddFileProgress {
    INTENT_ADMISSION,
    PLANNING,
    REVALIDATION,
    RECOVERY_PREPARATION,
    SOURCE_APPLICATION,
    WORKSPACE_PUBLICATION,
    PSI_ADMISSION,
}

enum class VerifiedAddFileFailure {
    WORKSPACE_MISMATCH,
    PLAN_NOT_FOUND,
    STALE_PLAN_VERSION,
    APPROVAL_REJECTED,
    TARGET_ALREADY_EXISTS,
    TARGET_GENERATED,
    TARGET_AMBIGUOUSLY_OWNED,
    TARGET_SYMLINK_ESCAPE,
    TARGET_NOT_WRITABLE,
    PACKAGE_OR_DECLARATION_INVALID,
    PLAN_REVALIDATION_FAILED,
    VCS_WRITE_PROMPT_REJECTED,
    SOURCE_APPLICATION_FAILED,
    PUBLICATION_FAILED,
    GENERATION_NOT_ADVANCED,
    PSI_NOT_ADMITTED,
    CANCELLED,
}

private const val INITIAL_VERSION = 0L
private const val TERMINAL_VERSION = 5L
