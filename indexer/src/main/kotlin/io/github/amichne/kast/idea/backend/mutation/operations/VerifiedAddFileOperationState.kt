package io.github.amichne.kast.idea.backend.mutation.operations

import io.github.amichne.kast.api.protocol.PartialApplyException
import io.github.amichne.kast.api.contract.result.AdditionKotlinPackage
import io.github.amichne.kast.api.contract.result.AdditionPostimageSha256
import io.github.amichne.kast.api.contract.result.AdditionTargetPath
import io.github.amichne.kast.api.contract.result.AdditionTopLevelDeclaration
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.result.MutationPostconditionEvidence
import io.github.amichne.kast.api.contract.result.MutationSemanticGeneration
import io.github.amichne.kast.server.change.RevalidatedVerifiedAddFilePlan
import io.github.amichne.kast.server.change.VerifiedAddFileApplyResult
import io.github.amichne.kast.server.change.VerifiedAddFileApprovalChallenge
import io.github.amichne.kast.server.change.VerifiedAddFileFailure
import io.github.amichne.kast.server.change.VerifiedAddFilePlan
import io.github.amichne.kast.server.change.VerifiedAddFilePlanId
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
    initialLifecycle: PersistedVerifiedAddFileLifecycle =
        PersistedVerifiedAddFileLifecycle.AwaitingApproval,
) {
    val approvalChallenge = VerifiedAddFileApprovalChallenge.persisted(planId, initialVersion, planned)
    val gate = Mutex()
    var lifecycle: PersistedVerifiedAddFileLifecycle = initialLifecycle
}

internal sealed interface PersistedVerifiedAddFileLifecycle {
    data object AwaitingApproval : PersistedVerifiedAddFileLifecycle

    data class ApplyOutcomeUnknown(
        val recovery: VerifiedAddFileRecoveryPrepared,
    ) : PersistedVerifiedAddFileLifecycle

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

internal sealed interface VerifiedAddFileJournalRead {
    data class Loaded(val plan: PersistedVerifiedAddFilePlan) : VerifiedAddFileJournalRead
    data class Rejected(val failure: VerifiedAddFileJournalFailure) : VerifiedAddFileJournalRead
}

internal sealed interface VerifiedAddFileJournalWrite {
    data object Stored : VerifiedAddFileJournalWrite
    data class Rejected(val failure: VerifiedAddFileJournalFailure) : VerifiedAddFileJournalWrite
}

internal enum class VerifiedAddFileJournalFailure { MISSING, CORRUPT, UNAVAILABLE }

internal sealed interface DurableLifecycleAdmission {
    data class Admitted(val lifecycle: PersistedVerifiedAddFileLifecycle) : DurableLifecycleAdmission
    data object Rejected : DurableLifecycleAdmission
}

internal sealed interface DurableApplicationAdmission {
    data class Admitted(val application: AppliedVerifiedAddFile) : DurableApplicationAdmission
    data object Rejected : DurableApplicationAdmission
}

internal sealed interface DurableRecoveryIdAdmission {
    data class Admitted(val recoveryId: VerifiedAddFileRecoveryId) : DurableRecoveryIdAdmission
    data object Rejected : DurableRecoveryIdAdmission
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

        /**
         * Proof transition: `(VerifiedAddFilePlan, VerifiedAddFileRecoveryId)`
         * to `VerifiedAddFileProofAdmission<VerifiedAddFileRecoveryPrepared>`.
         *
         * Re-admits a persisted recovery capability only when its identity is derived from the exact
         * strong plan. The closed failure is [VerifiedAddFileFailure.PLAN_NOT_FOUND]. Raw persisted
         * values may be supplied only by the workspace-scoped add-file journal boundary.
         */
        fun readmitPersisted(
            planned: VerifiedAddFilePlan,
            recoveryId: VerifiedAddFileRecoveryId,
        ): VerifiedAddFileProofAdmission<VerifiedAddFileRecoveryPrepared> {
            if (recoveryId != verifiedAddFileRecoveryId(planned)) {
                return VerifiedAddFileProofAdmission.Rejected(VerifiedAddFileFailure.PLAN_NOT_FOUND)
            }
            return when (val revalidation = RevalidatedVerifiedAddFilePlan.admit(planned, planned.exact)) {
                is io.github.amichne.kast.server.change.VerifiedAddFileAdmission.Admitted ->
                    VerifiedAddFileProofAdmission.Admitted(
                        VerifiedAddFileRecoveryPrepared(revalidation.value, recoveryId),
                    )
                is io.github.amichne.kast.server.change.VerifiedAddFileAdmission.Rejected ->
                    VerifiedAddFileProofAdmission.Rejected(VerifiedAddFileFailure.PLAN_NOT_FOUND)
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
         * Proof transition: `(VerifiedAddFileRecoveryPrepared, AdditionTargetPath,
         * AdditionPostimageSha256) -> VerifiedAddFileProofAdmission<AppliedVerifiedAddFile>`.
         *
         * Re-admits persisted application authority only when target and postimage equal the exact
         * compiler-backed plan. The closed failure is [VerifiedAddFileFailure.PLAN_NOT_FOUND]. Raw
         * persisted values may be supplied only by the workspace-scoped add-file journal boundary.
         */
        fun readmitPersisted(
            recovery: VerifiedAddFileRecoveryPrepared,
            targetPath: AdditionTargetPath,
            postimageSha256: AdditionPostimageSha256,
        ): VerifiedAddFileProofAdmission<AppliedVerifiedAddFile> {
            val proof = recovery.plan.planned.exact.proof
            return if (targetPath == proof.targetPath && postimageSha256 == proof.postimageSha256) {
                VerifiedAddFileProofAdmission.Admitted(
                    AppliedVerifiedAddFile(recovery, targetPath, postimageSha256),
                )
            } else {
                VerifiedAddFileProofAdmission.Rejected(VerifiedAddFileFailure.PLAN_NOT_FOUND)
            }
        }

        /**
         * Proof transition: `(VerifiedAddFileRecoveryPrepared, PartialApplyException)`
         * to `VerifiedAddFileProofAdmission<AppliedVerifiedAddFile>`.
         *
         * Establishes from structured backend evidence that this one-operation request committed
         * creation of exactly the planned target, no deletion, and no retained recovery artifact.
         * The closed expected failure is [VerifiedAddFileFailure.SOURCE_APPLICATION_FAILED]. Raw
         * exception details are extracted only at this partial-application admission boundary.
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
                failure.details["deletedFiles"].isNullOrEmpty() &&
                failure.details.keys.none { key ->
                    key == "recoveryFilePathCount" ||
                        key == "recoveryFilePath" ||
                        key.startsWith("recoveryFilePath.")
                }
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
