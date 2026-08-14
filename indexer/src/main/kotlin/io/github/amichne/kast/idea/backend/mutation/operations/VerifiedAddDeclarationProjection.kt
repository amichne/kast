package io.github.amichne.kast.idea.backend.mutation

import io.github.amichne.kast.change.apply.service.ApplyRecoveryPreparedAddDeclarationResult
import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.change.contract.AddDeclarationPlanId
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanStage
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanStateVersion
import io.github.amichne.kast.change.journal.contract.PersistedAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.VerifiedAddDeclaration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.server.change.VerifiedAddDeclarationApplyRequest
import io.github.amichne.kast.server.change.VerifiedAddDeclarationApplyResult
import io.github.amichne.kast.server.change.VerifiedAddDeclarationDeclarationIdentity
import io.github.amichne.kast.server.change.VerifiedAddDeclarationDeclarationKind
import io.github.amichne.kast.server.change.VerifiedAddDeclarationDeclarationName
import io.github.amichne.kast.server.change.VerifiedAddDeclarationPackageName
import io.github.amichne.kast.server.change.VerifiedAddDeclarationPlanId as WirePlanId
import io.github.amichne.kast.server.change.VerifiedAddDeclarationPlanPreview
import io.github.amichne.kast.server.change.VerifiedAddDeclarationPlanResult
import io.github.amichne.kast.server.change.VerifiedAddDeclarationPlanStage as WirePlanStage
import io.github.amichne.kast.server.change.VerifiedAddDeclarationPlanVersion as WirePlanVersion
import io.github.amichne.kast.server.change.VerifiedAddDeclarationPostimageSha256
import io.github.amichne.kast.server.change.VerifiedAddDeclarationProgress
import io.github.amichne.kast.server.change.VerifiedAddDeclarationProposedDeclaration
import io.github.amichne.kast.server.change.VerifiedAddDeclarationPublication
import io.github.amichne.kast.server.change.VerifiedAddDeclarationPublicationGeneration
import io.github.amichne.kast.server.change.VerifiedAddDeclarationReconciliationAction
import io.github.amichne.kast.server.change.VerifiedAddDeclarationRecoveryAction
import io.github.amichne.kast.server.change.VerifiedAddDeclarationRejection
import io.github.amichne.kast.server.change.VerifiedAddDeclarationSourceRange
import io.github.amichne.kast.server.change.VerifiedAddDeclarationTargetPath
import io.github.amichne.kast.server.change.VerifiedAddDeclarationWireRefinement
import io.github.amichne.kast.server.change.VerifiedAddDeclarationWorkspaceStateIdentity

internal data class WireAddDeclarationLifecycle(
    val planId: WirePlanId,
    val version: WirePlanVersion,
    val stage: WirePlanStage,
)

internal fun PersistedAddDeclarationPlan.AwaitingApproval.toVerifiedPlanResult():
    VerifiedAddDeclarationPlanResult.Planned = VerifiedAddDeclarationPlanResult.Planned(
    planId = plan.planId.toWirePlanId(),
    planVersion = version.toWirePlanVersion(),
    preview = VerifiedAddDeclarationPlanPreview(
        targetPath = wire(VerifiedAddDeclarationTargetPath.refine(plan.target.targetPath.value)),
        proposedDeclaration = wire(
            VerifiedAddDeclarationProposedDeclaration.refine(plan.intent.proposedDeclaration.value),
        ),
        generation = wire(
            VerifiedAddDeclarationPublicationGeneration.refine(plan.generation.value),
        ),
    ),
)

internal fun VerifiedAddDeclaration.toWireVerified(): VerifiedAddDeclarationApplyResult.Verified =
    VerifiedAddDeclarationApplyResult.Verified(
        planId = receipt.planId.toWirePlanId(),
        planVersion = version.toWirePlanVersion(),
        publication = VerifiedAddDeclarationPublication(
            generation = wire(
                VerifiedAddDeclarationPublicationGeneration.refine(
                    receipt.publication.generation.value,
                ),
            ),
            workspaceStateIdentity = wire(
                VerifiedAddDeclarationWorkspaceStateIdentity.refine(
                    receipt.publication.identity.value,
                ),
            ),
        ),
        identity = VerifiedAddDeclarationDeclarationIdentity(
            targetPath = wire(
                VerifiedAddDeclarationTargetPath.refine(receipt.identity.targetPath.value),
            ),
            sourceRange = wire(
                VerifiedAddDeclarationSourceRange.refine(
                    receipt.identity.sourceRange.startOffset,
                    receipt.identity.sourceRange.endOffset,
                ),
            ),
            packageName = wire(
                VerifiedAddDeclarationPackageName.refine(receipt.identity.packageName),
            ),
            declarationName = wire(
                VerifiedAddDeclarationDeclarationName.refine(receipt.identity.declarationName),
            ),
            declarationKind = receipt.identity.declarationKind.toWireKind(),
        ),
        postimageSha256 = wire(
            VerifiedAddDeclarationPostimageSha256.refine(receipt.postimageSha256.value),
        ),
    )

/**
 * Proof transition: [VerifiedAddDeclarationApplyRequest] to [AddDeclarationPlanId].
 *
 * The wire request already proves the same canonical lowercase SHA-256 invariant required by the
 * change contract. Raw extraction is permitted only at this cross-contract projection boundary;
 * rejection here is an internal contract contradiction, not an expected operation failure.
 */
internal fun VerifiedAddDeclarationApplyRequest.changePlanId(): AddDeclarationPlanId =
    when (val parsed = AddDeclarationPlanId.parse(planId.value)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error(
            "Verified wire PlanId violated the add-declaration change contract: ${parsed.failure}",
        )
    }

internal fun VerifiedAddDeclarationApplyRequest.lifecycle(
    stage: AddDeclarationPlanStage,
): WireAddDeclarationLifecycle = WireAddDeclarationLifecycle(
    planId = planId,
    version = expectedVersion,
    stage = stage.toWireStage(),
)

internal fun PersistedAddDeclarationPlan.toWireLifecycle(): WireAddDeclarationLifecycle =
    WireAddDeclarationLifecycle(
        planId = plan.planId.toWirePlanId(),
        version = version.toWirePlanVersion(),
        stage = stage.toWireStage(),
    )

internal fun rejected(
    lifecycle: WireAddDeclarationLifecycle,
    progress: VerifiedAddDeclarationProgress,
    failure: VerifiedAddDeclarationRejection,
): VerifiedAddDeclarationApplyResult.Rejected = VerifiedAddDeclarationApplyResult.Rejected(
    planId = lifecycle.planId,
    planVersion = lifecycle.version,
    stage = lifecycle.stage,
    progress = progress,
    failure = failure,
)

internal fun recoveryRequired(
    lifecycle: WireAddDeclarationLifecycle,
    progress: VerifiedAddDeclarationProgress,
    action: VerifiedAddDeclarationRecoveryAction,
): VerifiedAddDeclarationApplyResult.RecoveryRequired =
    VerifiedAddDeclarationApplyResult.RecoveryRequired(
        planId = lifecycle.planId,
        planVersion = lifecycle.version,
        stage = lifecycle.stage,
        progress = progress,
        action = action,
    )

internal fun reconciliation(
    lifecycle: WireAddDeclarationLifecycle,
    progress: VerifiedAddDeclarationProgress,
    action: VerifiedAddDeclarationReconciliationAction,
): VerifiedAddDeclarationApplyResult.ReconciliationRequired =
    VerifiedAddDeclarationApplyResult.ReconciliationRequired(
        planId = lifecycle.planId,
        planVersion = lifecycle.version,
        stage = lifecycle.stage,
        progress = progress,
        action = action,
    )

internal fun ApplyRecoveryPreparedAddDeclarationResult.strongestLifecycleOr(
    fallback: PersistedAddDeclarationPlan,
): WireAddDeclarationLifecycle = when (this) {
    is ApplyRecoveryPreparedAddDeclarationResult.AppliedUnverified -> record.toWireLifecycle()
    is ApplyRecoveryPreparedAddDeclarationResult.RejectedBeforeAdmission -> fallback.toWireLifecycle()
    is ApplyRecoveryPreparedAddDeclarationResult.ApplyAdmissionReconciliationRequired ->
        recoveryPrepared.record.toWireLifecycle()
    is ApplyRecoveryPreparedAddDeclarationResult.RecoveryRequiredBeforeMutation ->
        admitted.toWireLifecycle()
    is ApplyRecoveryPreparedAddDeclarationResult.RecoveryRequiredAfterMutation ->
        admitted.toWireLifecycle()
    is ApplyRecoveryPreparedAddDeclarationResult.RecoveryRequiredMutationOutcomeUnknown ->
        admitted.toWireLifecycle()
    is ApplyRecoveryPreparedAddDeclarationResult.CompletionReconciliationRequired ->
        admitted.toWireLifecycle()
}

private fun AddDeclarationPlanId.toWirePlanId(): WirePlanId =
    wire(WirePlanId.refine(value))

private fun AddDeclarationPlanStateVersion.toWirePlanVersion(): WirePlanVersion =
    wire(WirePlanVersion.refine(value))

private fun AddDeclarationPlanStage.toWireStage(): WirePlanStage = when (this) {
    AddDeclarationPlanStage.AWAITING_APPROVAL -> WirePlanStage.AWAITING_APPROVAL
    AddDeclarationPlanStage.APPROVED -> WirePlanStage.APPROVED
    AddDeclarationPlanStage.RECOVERY_PREPARED -> WirePlanStage.RECOVERY_PREPARED
    AddDeclarationPlanStage.APPLY_ADMITTED -> WirePlanStage.APPLY_ADMITTED
    AddDeclarationPlanStage.APPLIED_UNVERIFIED -> WirePlanStage.APPLIED_UNVERIFIED
    AddDeclarationPlanStage.VERIFIED -> WirePlanStage.VERIFIED
}

private fun AddDeclarationKind.toWireKind(): VerifiedAddDeclarationDeclarationKind = when (this) {
    AddDeclarationKind.CLASS -> VerifiedAddDeclarationDeclarationKind.CLASS
    AddDeclarationKind.INTERFACE -> VerifiedAddDeclarationDeclarationKind.INTERFACE
    AddDeclarationKind.OBJECT -> VerifiedAddDeclarationDeclarationKind.OBJECT
    AddDeclarationKind.ENUM_CLASS -> VerifiedAddDeclarationDeclarationKind.ENUM_CLASS
    AddDeclarationKind.ANNOTATION_CLASS -> VerifiedAddDeclarationDeclarationKind.ANNOTATION_CLASS
    AddDeclarationKind.FUNCTION -> VerifiedAddDeclarationDeclarationKind.FUNCTION
    AddDeclarationKind.PROPERTY -> VerifiedAddDeclarationDeclarationKind.PROPERTY
    AddDeclarationKind.TYPE_ALIAS -> VerifiedAddDeclarationDeclarationKind.TYPE_ALIAS
}

private fun <T> wire(refinement: VerifiedAddDeclarationWireRefinement<T>): T = when (refinement) {
    is VerifiedAddDeclarationWireRefinement.Refined -> refinement.value
    is VerifiedAddDeclarationWireRefinement.Rejected -> throw IllegalStateException(
        "Proven add-declaration evidence could not cross the verified wire: ${refinement.failure}",
    )
}
