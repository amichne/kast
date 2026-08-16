package io.github.amichne.kast.change.journal.contract

import io.github.amichne.kast.change.contract.AddDeclarationMutationProgress
import io.github.amichne.kast.change.contract.AddDeclarationRecoveryMaterial
import io.github.amichne.kast.change.contract.RevalidatedAddDeclaration
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable

@Serializable
enum class AddDeclarationRecoveryPreparationFailure {
    PLAN_MISMATCH,
    PRIOR_VERSION_MISMATCH,
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
    val revalidated: RevalidatedAddDeclaration,
    val expectedVersion: AddDeclarationPlanStateVersion,
) {
    companion object {
        /**
         * Proof transition:
         * revalidated plan and approved state version to
         * `Refinement<PrepareAddDeclarationRecovery,
         * AddDeclarationRecoveryPreparationRejection>`.
         *
         * Establishes a finite next state version for exact durable recovery preparation. The
         * closed expected failure is `AddDeclarationRecoveryPreparationRejection`, which proves
         * mutation has not begun; version extraction is permitted only by the journal adapter.
         */
        fun admit(
            revalidated: RevalidatedAddDeclaration,
            expectedVersion: AddDeclarationPlanStateVersion,
        ): Refinement<PrepareAddDeclarationRecovery, AddDeclarationRecoveryPreparationRejection> =
            if (expectedVersion.next() is Refinement.Rejected) {
                Refinement.Rejected(
                    AddDeclarationRecoveryPreparationRejection.beforeMutation(
                        AddDeclarationRecoveryPreparationFailure.VERSION_EXHAUSTED,
                    ),
                )
            } else {
                Refinement.Refined(PrepareAddDeclarationRecovery(revalidated, expectedVersion))
            }
    }
}

enum class RecoveryPreparedAddDeclarationRestoreFailure {
    PRIOR_VERSION_MISMATCH,
    CURRENT_VERSION_INVALID,
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
         * approved persisted plan and admitted recovery command to
         * `Refinement<RecoveryPreparedAddDeclaration,
         * AddDeclarationRecoveryPreparationRejection>`.
         *
         * Establishes the exact one-version transition for the same plan and prior version, with
         * byte-exact recovery evidence and mutation explicitly not begun. The closed expected
         * failure is `AddDeclarationRecoveryPreparationRejection`; no raw state is exposed.
         */
        fun prepare(
            prior: PersistedAddDeclarationPlan.Approved,
            command: PrepareAddDeclarationRecovery,
        ): Refinement<RecoveryPreparedAddDeclaration, AddDeclarationRecoveryPreparationRejection> {
            if (command.revalidated.plan != prior.plan) {
                return rejected(AddDeclarationRecoveryPreparationFailure.PLAN_MISMATCH)
            }
            if (command.expectedVersion != prior.version) {
                return rejected(AddDeclarationRecoveryPreparationFailure.PRIOR_VERSION_MISMATCH)
            }
            val next = command.expectedVersion.next().valueOrNull()
                       ?: return rejected(AddDeclarationRecoveryPreparationFailure.VERSION_EXHAUSTED)
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
         * exact recovery material, and that mutation has not begun. The closed expected failure is
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

        private fun rejected(
            failure: AddDeclarationRecoveryPreparationFailure,
        ): Refinement.Rejected<AddDeclarationRecoveryPreparationRejection> =
            Refinement.Rejected(AddDeclarationRecoveryPreparationRejection.beforeMutation(failure))
    }
}

private fun <T, F> Refinement<T, F>.valueOrNull(): T? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}
