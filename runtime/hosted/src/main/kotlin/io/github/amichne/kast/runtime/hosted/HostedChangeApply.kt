package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.amichne.kast.change.apply.AppliedUnverified
import io.github.amichne.kast.change.apply.LiveAppliedDurability
import io.github.amichne.kast.change.apply.LiveMutationAuthority
import io.github.amichne.kast.change.apply.LiveMutationCandidate
import io.github.amichne.kast.change.apply.LiveMutationPreparation
import io.github.amichne.kast.change.apply.LiveSourceWriteResult
import io.github.amichne.kast.change.apply.ObservedAbsentMutationSource
import io.github.amichne.kast.change.apply.ObservedMutationSource
import io.github.amichne.kast.change.apply.SourceObservationResult
import io.github.amichne.kast.change.apply.VerifiedLivePlanApproval
import io.github.amichne.kast.change.contract.LiveAddDeclarationChangePlan
import io.github.amichne.kast.change.contract.LiveChangeBasisComparison
import io.github.amichne.kast.change.intellij.HostedLiveSourceWriter
import io.github.amichne.kast.change.intellij.IntellijChangeSourceAdapter
import io.github.amichne.kast.change.protocol.liveChangeEvidence
import io.github.amichne.kast.change.protocol.protocolPreview
import io.github.amichne.kast.change.recovery.AddDeclarationRecoveryService
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyQualification
import io.github.amichne.kast.protocol.contract.ChangeApplyRecoveryReason
import io.github.amichne.kast.protocol.contract.ChangeApplyRejection
import io.github.amichne.kast.protocol.contract.ChangeApplyResult
import io.github.amichne.kast.protocol.contract.ChangeApplyUnverifiedReason
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedPreWriteObservation
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryService
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadContext
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadResult

internal typealias HostedApplyOutcome =
    OperationOutcome<ChangeApplyResult, ChangeApplyQualification, ChangeApplyRejection>

internal suspend fun applyHostedChange(
    project: Project,
    query: HostedQueryService,
    resources: HostedChangeResources,
    plan: LiveAddDeclarationChangePlan,
    approval: VerifiedLivePlanApproval,
): HostedApplyOutcome {
    when (val history = observeHostedApplication(resources, plan)) {
        HostedApplicationHistory.Unattempted -> Unit
        is HostedApplicationHistory.Terminal -> return history.outcome
    }
    val fresh =
        when (val observed = readFreshHostedMutation(project, query, plan)) {
            is Refinement.Refined -> observed.value
            is Refinement.Rejected -> return rejectionAfterHistory(resources, plan, observed.failure)
        }
    fresh.guard.use {
        val recovery = AddDeclarationRecoveryService(resources.journal)
        val authority =
            when (
                val prepared =
                    LiveMutationAuthority.prepare(
                        fresh.candidate(plan, approval),
                        resources.plans,
                        recovery,
                    )
            ) {
                is LiveMutationPreparation.Ready -> prepared.authority
                LiveMutationPreparation.AlreadyAttempted,
                LiveMutationPreparation.ClaimedWithoutRecovery ->
                    return recoveryRequired(plan, ChangeApplyRecoveryReason.ATTEMPT_INTERRUPTED)
                is LiveMutationPreparation.Rejected -> {
                    Logger.getInstance(HostedChangeCoordinator::class.java)
                        .info("kast_change stage=MUTATION_ADMISSION outcome=REJECTED failure=${prepared.failure}")
                    return rejectionAfterHistory(resources, plan, ChangeApplyRejection.WRITE_SCOPE_REJECTED)
                }
            }
        val durability = LiveAppliedDurability(authority, recovery)
        return when (val written = HostedLiveSourceWriter(project).write(authority, fresh.guard, durability)) {
            is LiveSourceWriteResult.Applied ->
                completeHostedApplication(
                    project = project,
                    query = query,
                    resources = resources,
                    write = written.write,
                    durability = durability.state,
                )
            is LiveSourceWriteResult.RejectedBeforeMutation ->
                recoveryRequired(plan, ChangeApplyRecoveryReason.ATTEMPT_INTERRUPTED)
            is LiveSourceWriteResult.RejectedAfterRollback ->
                recoveryRequired(plan, ChangeApplyRecoveryReason.DURABILITY_REJECTED)
            is LiveSourceWriteResult.RecoveryRequired ->
                recoveryRequired(plan, ChangeApplyRecoveryReason.WRITE_OUTCOME_UNKNOWN)
        }
    }
}

private suspend fun readFreshHostedMutation(
    project: Project,
    query: HostedQueryService,
    plan: LiveAddDeclarationChangePlan,
): Refinement<FreshHostedMutation, ChangeApplyRejection> =
    when (
        val read =
            query.read(query.endpoint, plan.basis.observation.reference.workspaceRoot) { context ->
                observeHostedMutation(project, context, plan)
            }
    ) {
        is HostedSemanticReadResult.Completed -> read.value
        is HostedSemanticReadResult.Rejected -> Refinement.Rejected(ChangeApplyRejection.CONTENT_CHANGED)
    }

private data class FreshHostedMutation(
    val authority: LiveSemanticReadAuthority,
    val model: WorkspaceSearchScopeModel,
    val source: ObservedMutationSource,
    val guard: HostedPreWriteObservation,
)

private fun FreshHostedMutation.candidate(
    plan: LiveAddDeclarationChangePlan,
    approval: VerifiedLivePlanApproval,
) = LiveMutationCandidate(plan = plan, current = authority, model = model, observed = source, approval = approval)

private fun observeHostedMutation(
    project: Project,
    context: HostedSemanticReadContext,
    plan: LiveAddDeclarationChangePlan,
): Refinement<FreshHostedMutation, ChangeApplyRejection> {
    if (
        plan.basis.observation.compare(context.authority.reference, context.model) !=
            LiveChangeBasisComparison.UNCHANGED
    ) {
        return Refinement.Rejected(ChangeApplyRejection.CONTENT_CHANGED)
    }
    val source =
        when (val observed = IntellijChangeSourceAdapter(project).observe(plan.target.file, context.limits)) {
            is SourceObservationResult.Observed ->
                when (val value = observed.source) {
                    is ObservedMutationSource -> value
                    is ObservedAbsentMutationSource -> return Refinement.Rejected(ChangeApplyRejection.CONTENT_CHANGED)
                }
            is SourceObservationResult.Rejected -> return Refinement.Rejected(ChangeApplyRejection.CONTENT_CHANGED)
        }
    val guard =
        when (
            val captured =
                context.prepareWriteObservation(plan.basis.observation.reference, plan.basis.observation.model)
        ) {
            is Refinement.Refined -> captured.value
            is Refinement.Rejected -> return Refinement.Rejected(ChangeApplyRejection.CONTENT_CHANGED)
        }
    return Refinement.Refined(
        FreshHostedMutation(authority = context.authority, model = context.model, source = source, guard = guard)
    )
}

internal fun recoveryRequired(
    plan: LiveAddDeclarationChangePlan,
    reason: ChangeApplyRecoveryReason,
): HostedApplyOutcome =
    OperationOutcome.Qualified(
        liveChangeEvidence(
            CanonicalOperation.CHANGE_APPLY,
            plan.basis.observation.reference,
            ChangeApplyResult.RecoveryRequired(
                hostedProtocolText("plan:${plan.planId.value}"),
                plan.protocolPreview(),
                reason,
            ),
        ),
        ChangeApplyQualification.RECOVERY_REQUIRED,
    )

internal fun unverified(plan: LiveAddDeclarationChangePlan, reason: ChangeApplyUnverifiedReason): HostedApplyOutcome =
    OperationOutcome.Qualified(
        liveChangeEvidence(
            CanonicalOperation.CHANGE_APPLY,
            plan.basis.observation.reference,
            ChangeApplyResult.AppliedUnverified(
                hostedProtocolText("plan:${plan.planId.value}"),
                plan.protocolPreview(),
                reason,
            ),
        ),
        ChangeApplyQualification.APPLIED_UNVERIFIED,
    )

internal fun hostedProtocolText(value: String): ProtocolText =
    when (val parsed = ProtocolText.parse(value)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("Typed bounded change identity cannot be projected")
    }
