package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.project.Project
import io.github.amichne.kast.change.apply.LiveRecoveryAuthority
import io.github.amichne.kast.change.apply.VerifiedLivePlanApproval
import io.github.amichne.kast.change.contract.LiveAddDeclarationChangePlan
import io.github.amichne.kast.change.contract.LiveChangeBasis
import io.github.amichne.kast.change.intellij.HostedLiveSourceRecovery
import io.github.amichne.kast.change.protocol.liveChangeEvidence
import io.github.amichne.kast.change.recovery.AddDeclarationRecoveryOutcome
import io.github.amichne.kast.change.recovery.AddDeclarationRecoveryService
import io.github.amichne.kast.change.recovery.AddDeclarationRollbackFailure
import io.github.amichne.kast.change.recovery.AddDeclarationRollbackResult
import io.github.amichne.kast.change.recovery.ConfirmedRecoveryPostimage
import io.github.amichne.kast.change.recovery.ConfirmedRecoveryPreimage
import io.github.amichne.kast.change.recovery.ExpectedRecoveryPostimage
import io.github.amichne.kast.change.recovery.RecoverySourceObservation
import io.github.amichne.kast.evidence.contract.MutationPlanBinding
import io.github.amichne.kast.evidence.contract.MutationRecoveryLoadResult
import io.github.amichne.kast.evidence.contract.MutationRecoveryPersistResult
import io.github.amichne.kast.evidence.contract.MutationRecoveryRecord
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeRecoverQualification
import io.github.amichne.kast.protocol.contract.ChangeRecoverRejection
import io.github.amichne.kast.protocol.contract.ChangeRecoverResult
import io.github.amichne.kast.protocol.contract.ChangeRecoveryDocumentState
import io.github.amichne.kast.workspace.contract.LiveSemanticReadReference
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedPreWriteObservation
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryService
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadContext
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadResult

internal typealias HostedRecoverOutcome =
    OperationOutcome<ChangeRecoverResult, ChangeRecoverQualification, ChangeRecoverRejection>

internal suspend fun recoverHostedChange(
    project: Project,
    query: HostedQueryService,
    resources: HostedChangeResources,
    plan: LiveAddDeclarationChangePlan,
    approval: VerifiedLivePlanApproval,
): HostedRecoverOutcome {
    val binding =
        when (val parsed = MutationPlanBinding.parse(plan.planId.value)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return OperationOutcome.Rejected(ChangeRecoverRejection.PLAN_NOT_FOUND)
        }
    val record =
        when (val loaded = resources.journal.load(binding)) {
            is MutationRecoveryLoadResult.Found -> loaded.record
            is MutationRecoveryLoadResult.Absent,
            is MutationRecoveryLoadResult.Rejected ->
                return OperationOutcome.Rejected(ChangeRecoverRejection.JOURNAL_UNAVAILABLE)
        }
    val read =
        query.read(query.endpoint, plan.basis.observation.reference.workspaceRoot) { context ->
            observeHostedRecovery(
                project = project,
                context = context,
                plan = plan,
                approval = approval,
                record = record,
            )
        }
    val fresh =
        when (read) {
            is HostedSemanticReadResult.Completed ->
                when (val observed = read.value) {
                    is Refinement.Refined -> observed.value
                    is Refinement.Rejected ->
                        return recoveryOutcome(
                            plan.basis.observation.reference,
                            ChangeRecoveryDocumentState.RECOVERY_REQUIRED,
                        )
                }
            is HostedSemanticReadResult.Rejected ->
                return recoveryOutcome(plan.basis.observation.reference, ChangeRecoveryDocumentState.RECOVERY_REQUIRED)
        }
    val state = fresh.guard.use { executeHostedRecovery(project, resources, fresh) }
    if (state == ChangeRecoveryDocumentState.RECOVERY_REQUIRED) return recoveryOutcome(fresh.authority.current, state)
    return completeHostedRecovery(
        project = project,
        query = query,
        expected = HostedRecoveryExpectation(plan, fresh.authority.current, record),
        state = state,
    )
}

private data class FreshHostedRecovery(
    val authority: LiveRecoveryAuthority,
    val source: RecoverySourceObservation,
    val guard: HostedPreWriteObservation,
)

private fun observeHostedRecovery(
    project: Project,
    context: HostedSemanticReadContext,
    plan: LiveAddDeclarationChangePlan,
    approval: VerifiedLivePlanApproval,
    record: MutationRecoveryRecord,
): Refinement<FreshHostedRecovery, ChangeRecoverRejection> {
    val rejected = Refinement.Rejected(ChangeRecoverRejection.RECOVERY_FAILED)
    val authority =
        when (
            val admitted =
                LiveRecoveryAuthority.admit(
                    plan = plan,
                    approval = approval,
                    current = context.authority,
                    model = context.model,
                    record = record,
                )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return rejected
        }
    val source = record.preparation.plannedWrites.single().source
    val observed =
        when (val result = HostedLiveSourceRecovery(project).observe(source, context.limits)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return rejected
        }
    val guard =
        when (val captured = context.prepareWriteObservation(context.authority.reference, context.model)) {
            is Refinement.Refined -> captured.value
            is Refinement.Rejected -> return rejected
        }
    return Refinement.Refined(FreshHostedRecovery(authority, observed, guard))
}

private fun executeHostedRecovery(
    project: Project,
    resources: HostedChangeResources,
    fresh: FreshHostedRecovery,
): ChangeRecoveryDocumentState {
    val record = fresh.authority.record
    val service = AddDeclarationRecoveryService(resources.journal)
    val applied =
        when (record) {
            is MutationRecoveryRecord.PreWriteDurable -> {
                if (ConfirmedRecoveryPreimage.admit(record, listOf(fresh.source)) is Refinement.Refined)
                    return ChangeRecoveryDocumentState.PRIOR_STATE
                val postimage =
                    when (
                        val observed =
                            ConfirmedRecoveryPostimage.admit(
                                record,
                                listOf(
                                    ExpectedRecoveryPostimage(
                                        fresh.source.source,
                                        fresh.authority.expectedRecoveryPostimage(),
                                    )
                                ),
                                listOf(fresh.source),
                            )
                    ) {
                        is Refinement.Refined -> observed.value
                        is Refinement.Rejected -> return ChangeRecoveryDocumentState.RECOVERY_REQUIRED
                    }
                when (val persisted = service.recordObservedApplied(postimage)) {
                    is MutationRecoveryPersistResult.Durable -> persisted.record
                    is MutationRecoveryPersistResult.Rejected -> return ChangeRecoveryDocumentState.RECOVERY_REQUIRED
                }
            }
            is MutationRecoveryRecord.AppliedWritesDurable -> record
            // Journal success is historical; the shared fresh completion read must still prove both images.
            is MutationRecoveryRecord.RolledBack -> return ChangeRecoveryDocumentState.ROLLED_BACK
            is MutationRecoveryRecord.RecoveryRequired -> return ChangeRecoveryDocumentState.RECOVERY_REQUIRED
        }
    val outcome =
        service.recover(applied.binding) { actual ->
            if (actual.digest != applied.digest)
                AddDeclarationRollbackResult.Rejected(AddDeclarationRollbackFailure.CONTENT_DIVERGED)
            else HostedLiveSourceRecovery(project).rollback(fresh.authority, fresh.guard)
        }
    return when (outcome) {
        is AddDeclarationRecoveryOutcome.PriorState -> ChangeRecoveryDocumentState.PRIOR_STATE
        is AddDeclarationRecoveryOutcome.RolledBack -> ChangeRecoveryDocumentState.ROLLED_BACK
        is AddDeclarationRecoveryOutcome.RecoveryRequired -> ChangeRecoveryDocumentState.RECOVERY_REQUIRED
    }
}

private suspend fun completeHostedRecovery(
    project: Project,
    query: HostedQueryService,
    expected: HostedRecoveryExpectation,
    state: ChangeRecoveryDocumentState,
): HostedRecoverOutcome {
    val required = recoveryOutcome(expected.before, ChangeRecoveryDocumentState.RECOVERY_REQUIRED)
    val read =
        query.read(query.endpoint, expected.before.workspaceRoot) { context ->
            observeRecoveryCompletion(project, context, expected)
        }
    val proof =
        when (read) {
            is HostedSemanticReadResult.Completed ->
                when (val observed = read.value) {
                    is Refinement.Refined -> observed.value
                    is Refinement.Rejected -> return required
                }
            is HostedSemanticReadResult.Rejected -> return required
        }
    com.intellij.openapi.diagnostic.Logger.getInstance(HostedChangeCoordinator::class.java)
        .info("kast_change stage=RECOVERY_OBSERVATION outcome=COMPLETED")
    return recoveryOutcome(proof.after.reference, state)
}

private fun observeRecoveryCompletion(
    project: Project,
    context: HostedSemanticReadContext,
    expected: HostedRecoveryExpectation,
): Refinement<HostedRecoveryPreimageProof, ChangeRecoverRejection> {
    val rejected = Refinement.Rejected(ChangeRecoverRejection.RECOVERY_FAILED)
    val after =
        when (val observed = LiveChangeBasis.observe(context.authority.reference, context.model)) {
            is Refinement.Refined -> observed.value
            is Refinement.Rejected -> return rejected
        }
    val source = expected.record.preparation.plannedWrites.single().source
    val observed =
        when (val result = HostedLiveSourceRecovery(project).observe(source, context.limits)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return rejected
        }
    return when (val proof = HostedRecoveryPreimageProof.admit(expected, after, observed)) {
        is Refinement.Refined -> proof
        is Refinement.Rejected -> {
            com.intellij.openapi.diagnostic.Logger.getInstance(HostedChangeCoordinator::class.java)
                .info("kast_change stage=RECOVERY_OBSERVATION outcome=REJECTED reason=${proof.failure}")
            rejected
        }
    }
}

private fun recoveryOutcome(
    reference: LiveSemanticReadReference,
    state: ChangeRecoveryDocumentState,
): HostedRecoverOutcome {
    val evidence = liveChangeEvidence(CanonicalOperation.CHANGE_RECOVER, reference, ChangeRecoverResult(state))
    return when (state) {
        ChangeRecoveryDocumentState.PRIOR_STATE,
        ChangeRecoveryDocumentState.ROLLED_BACK -> OperationOutcome.Complete(evidence)
        ChangeRecoveryDocumentState.RECOVERY_REQUIRED ->
            OperationOutcome.Qualified(evidence, ChangeRecoverQualification.MANUAL_RECOVERY_REQUIRED)
    }
}
