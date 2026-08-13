package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable

@Serializable
enum class AddDeclarationSourceProvenance {
    AUTHORED,
    GENERATED,
}

@Serializable
enum class AddDeclarationTargetWritability {
    WRITABLE,
    READ_ONLY,
}

enum class AddDeclarationRevalidationObservationFailure {
    TARGET_CONTENT_IDENTITY_MISMATCH,
}

@Serializable
@ConsistentCopyVisibility
data class AddDeclarationRevalidationObservation private constructor(
    val generation: AddDeclarationGeneration,
    val target: AddDeclarationTargetCapability,
    val currentFile: ExactFileContentProof,
    val provenance: AddDeclarationSourceProvenance,
    val writability: AddDeclarationTargetWritability,
) {
    companion object {
        /**
         * Proof transition:
         * current target facts to Refinement of AddDeclarationRevalidationObservation or
         * AddDeclarationRevalidationObservationFailure.
         *
         * Establishes one coherent detached observation whose target content identity equals its
         * exact current bytes. The closed expected failure is
         * AddDeclarationRevalidationObservationFailure; raw file, project-model, and writability
         * facts may be extracted only by the physical revalidation adapter.
         */
        fun observe(
            generation: EvidenceGeneration,
            target: AddDeclarationTargetCapability,
            currentFile: ExactFileContentProof,
            provenance: AddDeclarationSourceProvenance,
            writability: AddDeclarationTargetWritability,
        ): Refinement<
            AddDeclarationRevalidationObservation,
            AddDeclarationRevalidationObservationFailure,
            > {
            if (target.expectedCurrentSha256 != currentFile.sha256) {
                return Refinement.Rejected(
                    AddDeclarationRevalidationObservationFailure.TARGET_CONTENT_IDENTITY_MISMATCH,
                )
            }
            return Refinement.Refined(
                AddDeclarationRevalidationObservation(
                    generation = AddDeclarationGeneration.of(generation),
                    target = target,
                    currentFile = currentFile,
                    provenance = provenance,
                    writability = writability,
                ),
            )
        }
    }
}

@Serializable
enum class AddDeclarationMutationProgress {
    NOT_BEGUN,
    BEGUN,
}

@Serializable
enum class AddDeclarationRevalidationFailure {
    GENERATION_CHANGED,
    TARGET_IDENTITY_CHANGED,
    TARGET_CONTENT_CHANGED,
    OWNER_OR_SCOPE_CHANGED,
    PROVENANCE_CHANGED,
    TARGET_READ_ONLY,
}

@Serializable
@ConsistentCopyVisibility
data class AddDeclarationRevalidationRejection private constructor(
    val failure: AddDeclarationRevalidationFailure,
    val mutationProgress: AddDeclarationMutationProgress,
) {
    init {
        require(mutationProgress == AddDeclarationMutationProgress.NOT_BEGUN)
    }

    companion object {
        internal fun beforeMutation(
            failure: AddDeclarationRevalidationFailure,
        ): AddDeclarationRevalidationRejection =
            AddDeclarationRevalidationRejection(
                failure = failure,
                mutationProgress = AddDeclarationMutationProgress.NOT_BEGUN,
            )
    }
}

@Serializable
@ConsistentCopyVisibility
data class AddDeclarationRecoveryMaterial private constructor(
    val planId: AddDeclarationPlanId,
    val targetPath: AddDeclarationTargetPath,
    val beforeImage: ExactFileContentProof,
) {
    companion object {
        internal fun exact(
            plan: PlannedAddDeclaration,
            beforeImage: ExactFileContentProof,
        ): AddDeclarationRecoveryMaterial =
            AddDeclarationRecoveryMaterial(
                planId = plan.planId,
                targetPath = plan.target.targetPath,
                beforeImage = beforeImage,
            )

        /**
         * Proof transition:
         * stored PlanId, target path, and exact before image to
         * `Refinement<AddDeclarationRecoveryMaterial, AddDeclarationRecoveryMaterialFailure>`.
         *
         * Establishes that durable recovery material belongs to the exact plan, target, and
         * preimage proved during planning. The closed expected failure is
         * `AddDeclarationRecoveryMaterialFailure`; raw stored fields may be extracted only by the
         * durable journal decoder.
         */
        fun restore(
            plan: PlannedAddDeclaration,
            planId: AddDeclarationPlanId,
            targetPath: AddDeclarationTargetPath,
            beforeImage: ExactFileContentProof,
        ): Refinement<AddDeclarationRecoveryMaterial, AddDeclarationRecoveryMaterialFailure> =
            when {
                planId != plan.planId ->
                    Refinement.Rejected(AddDeclarationRecoveryMaterialFailure.PLAN_ID_MISMATCH)
                targetPath != plan.target.targetPath ->
                    Refinement.Rejected(AddDeclarationRecoveryMaterialFailure.TARGET_PATH_MISMATCH)
                beforeImage != plan.expectedFile.preimage ->
                    Refinement.Rejected(AddDeclarationRecoveryMaterialFailure.BEFORE_IMAGE_MISMATCH)
                else -> Refinement.Refined(exact(plan, beforeImage))
            }
    }
}

enum class AddDeclarationRecoveryMaterialFailure {
    PLAN_ID_MISMATCH,
    TARGET_PATH_MISMATCH,
    BEFORE_IMAGE_MISMATCH,
}

@ConsistentCopyVisibility
data class RevalidatedAddDeclaration private constructor(
    val plan: PlannedAddDeclaration,
    val generation: AddDeclarationGeneration,
    val target: AddDeclarationTargetCapability,
    val recovery: AddDeclarationRecoveryMaterial,
) {
    companion object {
        /**
         * Proof transition:
         * PlannedAddDeclaration and AddDeclarationRevalidationObservation to Refinement of
         * RevalidatedAddDeclaration or AddDeclarationRevalidationRejection.
         *
         * Establishes that current generation, target identity, owner and scope, exact content,
         * authored provenance, and writability equal the detached planning proof. The output
         * carries exact recovery material but no write capability. The closed expected failure is
         * AddDeclarationRevalidationRejection, which proves mutation did not begin. Raw image bytes
         * may be extracted only by the later durable recovery adapter.
         */
        fun admit(
            plan: PlannedAddDeclaration,
            observation: AddDeclarationRevalidationObservation,
        ): Refinement<RevalidatedAddDeclaration, AddDeclarationRevalidationRejection> {
            val mismatch = when {
                observation.generation != plan.generation ->
                    AddDeclarationRevalidationFailure.GENERATION_CHANGED
                observation.target.workspaceRoot != plan.target.workspaceRoot ||
                observation.target.targetPath != plan.target.targetPath ->
                    AddDeclarationRevalidationFailure.TARGET_IDENTITY_CHANGED
                observation.target.owner != plan.target.owner ->
                    AddDeclarationRevalidationFailure.OWNER_OR_SCOPE_CHANGED
                observation.target.expectedCurrentSha256 != plan.target.expectedCurrentSha256 ||
                observation.currentFile != plan.expectedFile.preimage ->
                    AddDeclarationRevalidationFailure.TARGET_CONTENT_CHANGED
                observation.provenance != AddDeclarationSourceProvenance.AUTHORED ->
                    AddDeclarationRevalidationFailure.PROVENANCE_CHANGED
                observation.writability != AddDeclarationTargetWritability.WRITABLE ->
                    AddDeclarationRevalidationFailure.TARGET_READ_ONLY
                else -> null
            }
            if (mismatch != null) {
                return Refinement.Rejected(
                    AddDeclarationRevalidationRejection.beforeMutation(mismatch),
                )
            }
            return Refinement.Refined(
                RevalidatedAddDeclaration(
                    plan = plan,
                    generation = observation.generation,
                    target = observation.target,
                    recovery = AddDeclarationRecoveryMaterial.exact(
                        plan = plan,
                        beforeImage = observation.currentFile,
                    ),
                ),
            )
        }
    }
}
