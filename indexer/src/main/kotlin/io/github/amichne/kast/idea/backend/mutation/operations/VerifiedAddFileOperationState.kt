package io.github.amichne.kast.idea.backend.mutation.operations

import io.github.amichne.kast.api.protocol.AdditionProofIncompleteException
import io.github.amichne.kast.api.protocol.AdditionProofLimitation
import io.github.amichne.kast.api.protocol.PartialApplyException
import io.github.amichne.kast.api.contract.result.AdditionKotlinPackage
import io.github.amichne.kast.api.contract.result.AdditionPostimageSha256
import io.github.amichne.kast.api.contract.result.AdditionTargetPath
import io.github.amichne.kast.api.contract.result.AdditionTopLevelDeclaration
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.result.MutationPostconditionEvidence
import io.github.amichne.kast.api.contract.result.MutationSemanticGeneration
import io.github.amichne.kast.server.change.RevalidatedVerifiedAddFilePlan
import io.github.amichne.kast.server.change.VerifiedAddFileApplyRequest
import io.github.amichne.kast.server.change.VerifiedAddFileApplyResult
import io.github.amichne.kast.server.change.VerifiedAddFileApprovalChallenge
import io.github.amichne.kast.server.change.VerifiedAddFileFailure
import io.github.amichne.kast.server.change.VerifiedAddFilePlan
import io.github.amichne.kast.server.change.VerifiedAddFilePlanId
import io.github.amichne.kast.server.change.VerifiedAddFilePlanStage
import io.github.amichne.kast.server.change.VerifiedAddFilePlanVersion
import io.github.amichne.kast.server.change.VerifiedAddFileProgress
import io.github.amichne.kast.server.change.VerifiedAddFileReceipt
import io.github.amichne.kast.server.change.VerifiedAddFileRecoveryDispositionAction
import io.github.amichne.kast.server.change.VerifiedAddFileRecoveryId
import io.github.amichne.kast.server.change.VerifiedAddFileReconciliationAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.coroutines.sync.Mutex

internal class PersistedVerifiedAddFilePlan(
    val planId: VerifiedAddFilePlanId,
    val initialVersion: VerifiedAddFilePlanVersion,
    val planned: VerifiedAddFilePlan,
) {
    val approvalChallenge = VerifiedAddFileApprovalChallenge.persisted(planId, initialVersion, planned)
    val gate = Mutex()
    var lifecycle: PersistedVerifiedAddFileLifecycle = PersistedVerifiedAddFileLifecycle.AwaitingApproval
}

internal sealed interface PersistedVerifiedAddFileLifecycle {
    data object AwaitingApproval : PersistedVerifiedAddFileLifecycle

    data class RecoveryRequired(
        val application: AppliedVerifiedAddFile,
        val progress: VerifiedAddFileProgress,
        val failure: VerifiedAddFileFailure,
        val action: VerifiedAddFileRecoveryDispositionAction,
    ) : PersistedVerifiedAddFileLifecycle

    data class ReconciliationRequired(
        val application: AppliedVerifiedAddFile,
        val progress: VerifiedAddFileProgress,
        val failure: VerifiedAddFileFailure,
        val action: VerifiedAddFileReconciliationAction,
    ) : PersistedVerifiedAddFileLifecycle

    data class NonDestructiveReconciliationRequired(
        val result: VerifiedAddFileApplyResult.ReconciliationRequired,
    ) : PersistedVerifiedAddFileLifecycle

    sealed interface Terminal : PersistedVerifiedAddFileLifecycle {
        data class Verified(val result: VerifiedAddFileApplyResult.Verified) : Terminal
        data class RolledBack(val result: VerifiedAddFileApplyResult.RolledBack) : Terminal
    }
}

internal sealed interface PlanAttempt {
    data class Planned(val plan: VerifiedAddFilePlan) : PlanAttempt
    data class Rejected(val result: VerifiedAddFileResult.Rejected) : PlanAttempt
}

internal sealed interface TargetAdmission {
    data object Admitted : TargetAdmission
    data class Rejected(val failure: VerifiedAddFileFailure) : TargetAdmission

    companion object {
        fun symlinkEscape(): Rejected = Rejected(VerifiedAddFileFailure.TARGET_SYMLINK_ESCAPE)
    }
}

internal fun rejected(
    progress: VerifiedAddFileProgress,
    failure: VerifiedAddFileFailure,
): VerifiedAddFileResult.Rejected = VerifiedAddFileResult.Rejected(progress, failure)

internal fun applyRejected(
    request: VerifiedAddFileApplyRequest,
    progress: VerifiedAddFileProgress,
    failure: VerifiedAddFileFailure,
): VerifiedAddFileApplyResult.Rejected = VerifiedAddFileApplyResult.Rejected(
    planId = request.planId,
    planVersion = request.expectedVersion,
    stage = progress.toStage(),
    progress = progress,
    failure = failure,
)

internal fun VerifiedAddFileProgress.toStage(): VerifiedAddFilePlanStage = when (this) {
    VerifiedAddFileProgress.INTENT_ADMISSION,
    VerifiedAddFileProgress.PLANNING,
    -> VerifiedAddFilePlanStage.AWAITING_APPROVAL
    VerifiedAddFileProgress.REVALIDATION -> VerifiedAddFilePlanStage.APPROVED
    VerifiedAddFileProgress.RECOVERY_PREPARATION -> VerifiedAddFilePlanStage.RECOVERY_PREPARED
    VerifiedAddFileProgress.SOURCE_APPLICATION -> VerifiedAddFilePlanStage.APPLY_ADMITTED
    VerifiedAddFileProgress.WORKSPACE_PUBLICATION,
    VerifiedAddFileProgress.PSI_ADMISSION,
    -> VerifiedAddFilePlanStage.APPLIED_UNVERIFIED
}

internal fun wireVersion(raw: Long): VerifiedAddFilePlanVersion =
    when (val refinement = VerifiedAddFilePlanVersion.refine(raw)) {
        is io.github.amichne.kast.server.change.VerifiedAddFileRefinement.Refined -> refinement.value
        is io.github.amichne.kast.server.change.VerifiedAddFileRefinement.Rejected -> error(
            "Static add-file lifecycle version violated its typed boundary: ${refinement.failure}",
        )
    }

internal fun AdditionProofIncompleteException.toVerifiedFailure(): VerifiedAddFileFailure = when {
    AdditionProofLimitation.TARGET_ALREADY_EXISTS in limitations -> VerifiedAddFileFailure.TARGET_ALREADY_EXISTS
    AdditionProofLimitation.GENERATED_SOURCE_READ_ONLY in limitations -> VerifiedAddFileFailure.TARGET_GENERATED
    AdditionProofLimitation.SOURCE_OWNER_AMBIGUOUS in limitations ->
        VerifiedAddFileFailure.TARGET_AMBIGUOUSLY_OWNED
    AdditionProofLimitation.OUTSIDE_WORKSPACE_AUTHORITY in limitations ||
        AdditionProofLimitation.HARD_EXCLUDED_MUTATION_TARGET in limitations ->
        VerifiedAddFileFailure.TARGET_SYMLINK_ESCAPE
    else -> VerifiedAddFileFailure.PACKAGE_OR_DECLARATION_INVALID
}

internal sealed interface VerifiedAddFileProofAdmission<out T> {
    data class Admitted<T>(val value: T) : VerifiedAddFileProofAdmission<T>
    data class Rejected(val failure: VerifiedAddFileFailure) : VerifiedAddFileProofAdmission<Nothing>
}

internal class VerifiedAddFileRecoveryPrepared private constructor(
    val plan: RevalidatedVerifiedAddFilePlan,
    val recoveryId: VerifiedAddFileRecoveryId,
) {
    companion object {
        /**
         * Proof transition: `(Path, RevalidatedVerifiedAddFilePlan, VerifiedAddFileRecoveryId)`
         * to `VerifiedAddFileProofAdmission<VerifiedAddFileRecoveryPrepared>`.
         *
         * Establishes exact-target absence, canonical workspace containment, and a writable existing
         * parent. Closed failures are target existence, writability, and symlink escape failures.
         * Raw paths are extracted only at this operation-specific recovery-preparation boundary.
         */
        fun admit(
            workspaceRoot: Path,
            revalidated: RevalidatedVerifiedAddFilePlan,
            recoveryId: VerifiedAddFileRecoveryId,
        ): VerifiedAddFileProofAdmission<VerifiedAddFileRecoveryPrepared> {
            val target = Path.of(revalidated.planned.intent.targetPath.value)
            val parent = target.parent
                ?: return VerifiedAddFileProofAdmission.Rejected(
                    VerifiedAddFileFailure.TARGET_SYMLINK_ESCAPE,
                )
            if (Files.exists(target, NOFOLLOW_LINKS)) {
                return VerifiedAddFileProofAdmission.Rejected(
                    VerifiedAddFileFailure.TARGET_ALREADY_EXISTS,
                )
            }
            if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) {
                return VerifiedAddFileProofAdmission.Rejected(
                    VerifiedAddFileFailure.TARGET_SYMLINK_ESCAPE,
                )
            }
            if (!Files.isWritable(parent)) {
                return VerifiedAddFileProofAdmission.Rejected(
                    VerifiedAddFileFailure.TARGET_NOT_WRITABLE,
                )
            }
            val canonicalRoot = runCatching { workspaceRoot.toRealPath() }.getOrNull()
                ?: return VerifiedAddFileProofAdmission.Rejected(
                    VerifiedAddFileFailure.TARGET_SYMLINK_ESCAPE,
                )
            val canonicalParent = runCatching { parent.toRealPath() }.getOrNull()
                ?: return VerifiedAddFileProofAdmission.Rejected(
                    VerifiedAddFileFailure.TARGET_SYMLINK_ESCAPE,
                )
            val canonicalTarget = canonicalParent.resolve(target.fileName).normalize()
            return if (
                canonicalParent == parent.toAbsolutePath().normalize() &&
                canonicalTarget.startsWith(canonicalRoot)
            ) {
                VerifiedAddFileProofAdmission.Admitted(
                    VerifiedAddFileRecoveryPrepared(revalidated, recoveryId),
                )
            } else {
                VerifiedAddFileProofAdmission.Rejected(
                    VerifiedAddFileFailure.TARGET_SYMLINK_ESCAPE,
                )
            }
        }
    }
}

internal class AppliedVerifiedAddFile private constructor(
    val recovery: VerifiedAddFileRecoveryPrepared,
    val targetPath: AdditionTargetPath,
    val postimageSha256: AdditionPostimageSha256,
) {
    companion object {
        /**
         * Proof transition: `(VerifiedAddFileRecoveryPrepared, ApplyEditsResult)`
         * to `VerifiedAddFileProofAdmission<AppliedVerifiedAddFile>`.
         *
         * Establishes that the exact planned target, and no other file, was created. The closed
         * expected failure is [VerifiedAddFileFailure.SOURCE_APPLICATION_FAILED].
         */
        fun admit(
            recovery: VerifiedAddFileRecoveryPrepared,
            applied: ApplyEditsResult,
        ): VerifiedAddFileProofAdmission<AppliedVerifiedAddFile> {
            val proof = recovery.plan.planned.exact.proof
            return if (applied.createdFiles == listOf(proof.targetPath.value)) {
                VerifiedAddFileProofAdmission.Admitted(
                    AppliedVerifiedAddFile(recovery, proof.targetPath, proof.postimageSha256),
                )
            } else {
                VerifiedAddFileProofAdmission.Rejected(
                    VerifiedAddFileFailure.SOURCE_APPLICATION_FAILED,
                )
            }
        }

        /**
         * Proof transition: `(VerifiedAddFileRecoveryPrepared, PartialApplyException)`
         * to `VerifiedAddFileProofAdmission<AppliedVerifiedAddFile>`.
         *
         * Establishes from structured backend evidence that this one-operation request committed
         * creation of exactly the planned target and no deletion. The closed expected failure is
         * [VerifiedAddFileFailure.SOURCE_APPLICATION_FAILED]. Raw exception details are extracted
         * only at this partial-application admission boundary.
         */
        fun admit(
            recovery: VerifiedAddFileRecoveryPrepared,
            failure: PartialApplyException,
        ): VerifiedAddFileProofAdmission<AppliedVerifiedAddFile> {
            val proof = recovery.plan.planned.exact.proof
            return if (
                failure.details["failedFile"] == proof.targetPath.value &&
                failure.details["appliedFiles"] == proof.targetPath.value &&
                failure.details["createdFiles"] == proof.targetPath.value &&
                failure.details["deletedFiles"].isNullOrEmpty()
            ) {
                VerifiedAddFileProofAdmission.Admitted(
                    AppliedVerifiedAddFile(recovery, proof.targetPath, proof.postimageSha256),
                )
            } else {
                VerifiedAddFileProofAdmission.Rejected(
                    VerifiedAddFileFailure.SOURCE_APPLICATION_FAILED,
                )
            }
        }
    }
}

internal class VerifiedAddFileTransition private constructor(
    val applied: AppliedVerifiedAddFile,
    val generation: MutationSemanticGeneration,
) {
    companion object {
        /**
         * Proof transition: `(AppliedVerifiedAddFile, MutationSemanticGeneration)`
         * to `VerifiedAddFileProofAdmission<VerifiedAddFileTransition>`.
         *
         * Establishes a semantic generation strictly newer than the planned generation. The closed
         * expected failure is [VerifiedAddFileFailure.GENERATION_NOT_ADVANCED].
         */
        fun admit(
            applied: AppliedVerifiedAddFile,
            generation: MutationSemanticGeneration,
        ): VerifiedAddFileProofAdmission<VerifiedAddFileTransition> =
            if (
                generation.value >
                applied.recovery.plan.planned.exact.proof.context.requiredGeneration.value
            ) {
                VerifiedAddFileProofAdmission.Admitted(
                    VerifiedAddFileTransition(applied, generation),
                )
            } else {
                VerifiedAddFileProofAdmission.Rejected(
                    VerifiedAddFileFailure.GENERATION_NOT_ADVANCED,
                )
            }
    }
}

internal class VerifiedAddFileVerification private constructor(
    val transition: VerifiedAddFileTransition,
    val packageIdentity: AdditionKotlinPackage,
    val declarations: List<AdditionTopLevelDeclaration>,
) {
    companion object {
        /**
         * Proof transition: `(VerifiedAddFileTransition, MutationPostconditionEvidence.AddFile)`
         * to `VerifiedAddFileProofAdmission<VerifiedAddFileVerification>`.
         *
         * Establishes exact agreement with the planned nonempty Kotlin package/declaration proof.
         * The closed expected failure is [VerifiedAddFileFailure.PSI_NOT_ADMITTED].
         */
        fun admit(
            transition: VerifiedAddFileTransition,
            evidence: MutationPostconditionEvidence.AddFile,
        ): VerifiedAddFileProofAdmission<VerifiedAddFileVerification> {
            val planned = transition.applied.recovery.plan.planned.exact.proof
            return if (
                evidence.declarations.isNotEmpty() &&
                evidence.packageIdentity == planned.packageIdentity &&
                evidence.declarations == planned.declarations
            ) {
                VerifiedAddFileProofAdmission.Admitted(
                    VerifiedAddFileVerification(
                        transition,
                        evidence.packageIdentity,
                        evidence.declarations.toList(),
                    ),
                )
            } else {
                VerifiedAddFileProofAdmission.Rejected(VerifiedAddFileFailure.PSI_NOT_ADMITTED)
            }
        }
    }

    fun toReceipt(): VerifiedAddFileReceipt = VerifiedAddFileReceipt(
        targetPath = transition.applied.targetPath,
        postimageSha256 = transition.applied.postimageSha256,
        generation = transition.generation,
        packageIdentity = packageIdentity,
        declarations = declarations,
    )
}

internal sealed interface VerifiedAddFileResult {
    data class Verified(val receipt: VerifiedAddFileReceipt) : VerifiedAddFileResult
    data class Rejected(
        val progress: VerifiedAddFileProgress,
        val failure: VerifiedAddFileFailure,
    ) : VerifiedAddFileResult
    data class RolledBack(
        val progress: VerifiedAddFileProgress,
        val failure: VerifiedAddFileFailure,
        val action: VerifiedAddFileRecoveryDispositionAction,
    ) : VerifiedAddFileResult
    data class RecoveryRequired(
        val application: AppliedVerifiedAddFile,
        val progress: VerifiedAddFileProgress,
        val failure: VerifiedAddFileFailure,
        val action: VerifiedAddFileRecoveryDispositionAction,
    ) : VerifiedAddFileResult
    data class ReconciliationRequired(
        val application: AppliedVerifiedAddFile,
        val progress: VerifiedAddFileProgress,
        val failure: VerifiedAddFileFailure,
        val action: VerifiedAddFileReconciliationAction,
    ) : VerifiedAddFileResult
    data class NonDestructiveReconciliationRequired(
        val recoveryId: VerifiedAddFileRecoveryId,
        val progress: VerifiedAddFileProgress,
        val failure: VerifiedAddFileFailure,
        val action: VerifiedAddFileReconciliationAction,
    ) : VerifiedAddFileResult
}
