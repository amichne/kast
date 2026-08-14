package io.github.amichne.kast.change.journal.contract

import io.github.amichne.kast.change.contract.AddDeclarationMutationProgress
import io.github.amichne.kast.change.contract.AddDeclarationRecoveryMaterial
import io.github.amichne.kast.change.contract.RevalidatedAddDeclaration
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable

@Serializable
enum class AddDeclarationRecoveryPreparationFailure {
    PLAN_MISMATCH,
    VERSION_EXHAUSTED,
}

@Serializable
@ConsistentCopyVisibility
data class AddDeclarationRecoveryPreparationRejection private constructor(
    val failure: AddDeclarationRecoveryPreparationFailure,
    val mutationProgress: AddDeclarationMutationProgress,
) {
    init {
        require(mutationProgress == AddDeclarationMutationProgress.NOT_BEGUN)
    }

    companion object {
        internal fun beforeMutation(
            failure: AddDeclarationRecoveryPreparationFailure,
        ): AddDeclarationRecoveryPreparationRejection =
            AddDeclarationRecoveryPreparationRejection(
                failure = failure,
                mutationProgress = AddDeclarationMutationProgress.NOT_BEGUN,
            )
    }
}

@ConsistentCopyVisibility
data class PrepareAddDeclarationRecovery private constructor(
    val approved: PersistedAddDeclarationPlan.Approved,
    val revalidated: RevalidatedAddDeclaration,
) {
    val expectedVersion: AddDeclarationPlanStateVersion
        get() = approved.version

    companion object {
        /**
         * Proof transition:
         * approved persisted plan plus revalidated add-declaration to
         * `Refinement<PrepareAddDeclarationRecovery,
         * AddDeclarationRecoveryPreparationRejection>`.
         *
         * Establishes that revalidation belongs to the exact approved plan and that one finite next
         * lifecycle version exists. The closed expected failure is
         * `AddDeclarationRecoveryPreparationRejection`, which proves mutation has not begun; raw
         * version extraction is permitted only by the journal adapter.
         */
        fun admit(
            approved: PersistedAddDeclarationPlan.Approved,
            revalidated: RevalidatedAddDeclaration,
        ): Refinement<PrepareAddDeclarationRecovery, AddDeclarationRecoveryPreparationRejection> {
            if (revalidated.plan != approved.plan) {
                return rejected(AddDeclarationRecoveryPreparationFailure.PLAN_MISMATCH)
            }
            if (approved.version.next() is Refinement.Rejected) {
                return rejected(AddDeclarationRecoveryPreparationFailure.VERSION_EXHAUSTED)
            }
            return Refinement.Refined(PrepareAddDeclarationRecovery(approved, revalidated))
        }

        private fun rejected(
            failure: AddDeclarationRecoveryPreparationFailure,
        ): Refinement.Rejected<AddDeclarationRecoveryPreparationRejection> =
            Refinement.Rejected(AddDeclarationRecoveryPreparationRejection.beforeMutation(failure))
    }
}

enum class RecoveryPreparedAddDeclarationRestoreFailure {
    PRIOR_VERSION_MISMATCH,
    CURRENT_VERSION_INVALID,
    RECOVERY_MATERIAL_MISMATCH,
    MUTATION_PROGRESS_INVALID,
}

@ConsistentCopyVisibility
data class RecoveryPreparedAddDeclaration private constructor(
    override val plan: io.github.amichne.kast.change.contract.PlannedAddDeclaration,
    override val version: AddDeclarationPlanStateVersion,
    val priorStage: AddDeclarationPlanStage,
    val priorVersion: AddDeclarationPlanStateVersion,
    val approvalEvidence: AddDeclarationPlanApprovalEvidence,
    val recovery: AddDeclarationRecoveryMaterial,
    val mutationProgress: AddDeclarationMutationProgress,
) : PersistedAddDeclarationPlan {
    override val stage: AddDeclarationPlanStage = AddDeclarationPlanStage.RECOVERY_PREPARED

    companion object {
        /**
         * Proof transition:
         * `PrepareAddDeclarationRecovery -> Refinement<RecoveryPreparedAddDeclaration,
         * AddDeclarationRecoveryPreparationRejection>`.
         *
         * Establishes one exact adjacent lifecycle version with byte-exact recovery evidence and
         * mutation explicitly not begun. The closed expected failure is
         * `AddDeclarationRecoveryPreparationRejection`; no raw state is exposed.
         */
        fun prepare(
            command: PrepareAddDeclarationRecovery,
        ): Refinement<RecoveryPreparedAddDeclaration, AddDeclarationRecoveryPreparationRejection> {
            val next = command.expectedVersion.next().valueOrNull()
                       ?: return Refinement.Rejected(
                           AddDeclarationRecoveryPreparationRejection.beforeMutation(
                               AddDeclarationRecoveryPreparationFailure.VERSION_EXHAUSTED,
                           ),
                       )
            val prior = command.approved
            return Refinement.Refined(
                RecoveryPreparedAddDeclaration(
                    plan = prior.plan,
                    version = next,
                    priorStage = prior.stage,
                    priorVersion = prior.version,
                    approvalEvidence = prior.approvalEvidence,
                    recovery = command.revalidated.recovery,
                    mutationProgress = AddDeclarationMutationProgress.NOT_BEGUN,
                ),
            )
        }

        /**
         * Proof transition:
         * approved persisted plan plus stored recovery fields to
         * `Refinement<RecoveryPreparedAddDeclaration,
         * RecoveryPreparedAddDeclarationRestoreFailure>`.
         *
         * Replays the exact approved-to-recovery-prepared transition and proves adjacent versions,
         * exact recovery material, and mutation not begun. The closed expected failure is
         * `RecoveryPreparedAddDeclarationRestoreFailure`; raw storage values may be extracted only
         * by the journal record decoder.
         */
        fun restore(
            prior: PersistedAddDeclarationPlan.Approved,
            currentVersion: AddDeclarationPlanStateVersion,
            priorVersion: AddDeclarationPlanStateVersion,
            recovery: AddDeclarationRecoveryMaterial,
            mutationProgress: AddDeclarationMutationProgress,
        ): Refinement<RecoveryPreparedAddDeclaration, RecoveryPreparedAddDeclarationRestoreFailure> {
            if (priorVersion != prior.version) {
                return Refinement.Rejected(
                    RecoveryPreparedAddDeclarationRestoreFailure.PRIOR_VERSION_MISMATCH,
                )
            }
            val expectedCurrent = priorVersion.next().valueOrNull()
                                  ?: return Refinement.Rejected(
                                      RecoveryPreparedAddDeclarationRestoreFailure.CURRENT_VERSION_INVALID,
                                  )
            if (currentVersion != expectedCurrent) {
                return Refinement.Rejected(
                    RecoveryPreparedAddDeclarationRestoreFailure.CURRENT_VERSION_INVALID,
                )
            }
            if (recovery.planId != prior.plan.planId ||
                recovery.targetPath != prior.plan.target.targetPath ||
                recovery.beforeImage != prior.plan.expectedFile.preimage
            ) {
                return Refinement.Rejected(
                    RecoveryPreparedAddDeclarationRestoreFailure.RECOVERY_MATERIAL_MISMATCH,
                )
            }
            if (mutationProgress != AddDeclarationMutationProgress.NOT_BEGUN) {
                return Refinement.Rejected(
                    RecoveryPreparedAddDeclarationRestoreFailure.MUTATION_PROGRESS_INVALID,
                )
            }
            return Refinement.Refined(
                RecoveryPreparedAddDeclaration(
                    plan = prior.plan,
                    version = currentVersion,
                    priorStage = prior.stage,
                    priorVersion = priorVersion,
                    approvalEvidence = prior.approvalEvidence,
                    recovery = recovery,
                    mutationProgress = mutationProgress,
                ),
            )
        }
    }
}

private fun <T, F> Refinement<T, F>.valueOrNull(): T? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}
