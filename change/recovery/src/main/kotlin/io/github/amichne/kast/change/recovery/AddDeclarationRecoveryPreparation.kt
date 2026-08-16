package io.github.amichne.kast.change.recovery

import io.github.amichne.kast.change.contract.AddDeclarationPlanId
import io.github.amichne.kast.change.contract.ChangePlan
import io.github.amichne.kast.evidence.contract.MutationPlanBinding
import io.github.amichne.kast.evidence.contract.RecoveryPreimage
import io.github.amichne.kast.evidence.contract.RecoverySourcePath
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash

enum class AddDeclarationRecoveryPreparationFailure {
    PLAN_BINDING_INVALID,
    SOURCE_PATH_INVALID,
    PREIMAGE_MISMATCH,
    WRITE_SET_NOT_SINGLETON,
}

/** Exact semantic change-plan binding plus hash-proven source preimage. */
class AddDeclarationRecoveryPreparation private constructor(
    val planId: AddDeclarationPlanId,
    val binding: MutationPlanBinding,
    val source: RecoverySourcePath,
    val expectedContent: WorkspaceSourceContentHash,
    val preimage: RecoveryPreimage,
) {
    companion object {
        /**
         * Proof transition: `(AddDeclarationPlanId, RecoverySourcePath,
         * WorkspaceSourceContentHash, RecoveryPreimage) -> Refinement<
         * AddDeclarationRecoveryPreparation, AddDeclarationRecoveryPreparationFailure>`.
         *
         * Establishes one tamper-evident plan binding whose exact before bytes calculate to the
         * plan's expected source identity. [AddDeclarationRecoveryPreparationFailure] is the
         * closed expected failure. Raw plan identity and source bytes may enter only at this
         * recovery-admission boundary and leave only at SQLite or physical recovery.
         */
        fun admit(
            planId: AddDeclarationPlanId,
            source: RecoverySourcePath,
            expectedContent: WorkspaceSourceContentHash,
            preimage: RecoveryPreimage,
        ): Refinement<AddDeclarationRecoveryPreparation, AddDeclarationRecoveryPreparationFailure> {
            val binding = when (val parsed = MutationPlanBinding.parse(planId.value)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return Refinement.Rejected(
                    AddDeclarationRecoveryPreparationFailure.PLAN_BINDING_INVALID,
                )
            }
            if (preimage.digest.value != expectedContent.value) {
                return Refinement.Rejected(
                    AddDeclarationRecoveryPreparationFailure.PREIMAGE_MISMATCH,
                )
            }
            return Refinement.Refined(
                AddDeclarationRecoveryPreparation(
                    planId,
                    binding,
                    source,
                    expectedContent,
                    preimage,
                ),
            )
        }

        /**
         * Proof transition: `(ChangePlan, RecoveryPreimage) -> Refinement<
         * AddDeclarationRecoveryPreparation, AddDeclarationRecoveryPreparationFailure>`.
         *
         * Establishes recovery material for the plan's exact target and source snapshot.
         * [AddDeclarationRecoveryPreparationFailure] is the closed expected failure. Raw target
         * path extraction occurs only while crossing from the plan into the generic evidence
         * contract; source bytes leave only at SQLite or physical recovery.
         */
        fun fromPlan(
            plan: ChangePlan,
            preimage: RecoveryPreimage,
        ): Refinement<AddDeclarationRecoveryPreparation, AddDeclarationRecoveryPreparationFailure> {
            if (plan.writes.entries.size != 1) {
                return Refinement.Rejected(
                    AddDeclarationRecoveryPreparationFailure.WRITE_SET_NOT_SINGLETON,
                )
            }
            val write = plan.writes.entries.single()
            val source = when (val parsed = RecoverySourcePath.parse(write.source.path.value)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return Refinement.Rejected(
                    AddDeclarationRecoveryPreparationFailure.SOURCE_PATH_INVALID,
                )
            }
            return admit(plan.planId, source, write.expectedContent, preimage)
        }
    }
}
