package io.github.amichne.kast.idea.backend.mutation

import io.github.amichne.kast.api.contract.query.AddDeclarationPlanQuery
import io.github.amichne.kast.api.contract.result.AdditionTargetPath
import io.github.amichne.kast.api.contract.result.AdditionTargetPreimageSha256
import io.github.amichne.kast.api.protocol.AdditionProofIncompleteException
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.change.apply.intellij.IntellijAddDeclarationApplyExecutor
import io.github.amichne.kast.change.apply.service.AddDeclarationApplicationService
import io.github.amichne.kast.change.apply.service.ApplyRecoveryPreparedAddDeclarationResult
import io.github.amichne.kast.change.contract.AddDeclarationIntellijRuntimeAuthority
import io.github.amichne.kast.change.contract.AddDeclarationRevalidationObservation
import io.github.amichne.kast.change.contract.AddDeclarationSourceProvenance
import io.github.amichne.kast.change.contract.AddDeclarationTargetWritability
import io.github.amichne.kast.change.contract.RevalidatedAddDeclaration
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanStage
import io.github.amichne.kast.change.journal.contract.ApproveAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.ApproveAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.LoadAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.PersistedAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.RawAddDeclarationPlanApprovalEvidence
import io.github.amichne.kast.change.journal.contract.StoreAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.sqlite.SqliteAddDeclarationPlanJournal
import io.github.amichne.kast.change.recovery.filesystem.FilesystemAddDeclarationRecoveryPreparer
import io.github.amichne.kast.change.recovery.service.AddDeclarationRecoveryPreparationService
import io.github.amichne.kast.change.recovery.service.PrepareApprovedAddDeclarationRecoveryResult
import io.github.amichne.kast.change.verify.intellij.IntellijPublishedWorkspaceGenerationAuthority
import io.github.amichne.kast.change.verify.service.AddDeclarationVerificationService
import io.github.amichne.kast.change.verify.service.VerifyAppliedAddDeclarationResult
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.mutation.SecureSourceProofRead
import io.github.amichne.kast.idea.mutation.SecureSourceProofReadOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.server.change.NativeVerifiedAddDeclarationOperations
import io.github.amichne.kast.server.change.VerifiedAddDeclarationApplyRequest
import io.github.amichne.kast.server.change.VerifiedAddDeclarationApplyResult
import io.github.amichne.kast.server.change.VerifiedAddDeclarationPlanRequest
import io.github.amichne.kast.server.change.VerifiedAddDeclarationPlanResult
import io.github.amichne.kast.server.change.VerifiedAddDeclarationPlanningFailure
import io.github.amichne.kast.server.change.VerifiedAddDeclarationProgress
import io.github.amichne.kast.server.change.VerifiedAddDeclarationReconciliationAction
import io.github.amichne.kast.server.change.VerifiedAddDeclarationRecoveryAction
import io.github.amichne.kast.server.change.VerifiedAddDeclarationRejection
import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import io.github.amichne.kast.workspace.spi.WorkspaceMutationTransitionOutcome
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionPort
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Proof transition: admitted indexer mutation capabilities to
 * [NativeVerifiedAddDeclarationOperations].
 *
 * The returned binding is the sole KIP-036 composition of durable planning, exact approval and
 * revalidation, forced recovery preparation, IntelliJ apply, workspace publication, live PSI/K2
 * verification, and terminal v5 persistence. Expected operation failures remain closed by the
 * server result families. Raw paths and wire values are extracted only at the adapters named by
 * each stage.
 */
internal fun KastIndexerBackend.verifiedAddDeclarationOperations(
    workspaceRoot: Path,
    journal: SqliteAddDeclarationPlanJournal,
    transitions: WorkspaceTransitionPort,
    publications: IntellijPublishedWorkspaceGenerationAuthority,
    recoveryPreparer: FilesystemAddDeclarationRecoveryPreparer,
    runtime: AddDeclarationIntellijRuntimeAuthority,
): NativeVerifiedAddDeclarationOperations = IntellijVerifiedAddDeclarationOperations(
    backend = this,
    workspaceRoot = workspaceRoot.toAbsolutePath().normalize(),
    journal = journal,
    recovery = AddDeclarationRecoveryPreparationService(journal, recoveryPreparer),
    application = AddDeclarationApplicationService(
        journal,
        IntellijAddDeclarationApplyExecutor(project, runtime),
    ),
    verification = AddDeclarationVerificationService(
        transitions,
        addDeclarationVerificationExecutor(publications, runtime),
        journal,
    ),
    transitions = transitions,
)

private class IntellijVerifiedAddDeclarationOperations(
    private val backend: KastIndexerBackend,
    private val workspaceRoot: Path,
    private val journal: SqliteAddDeclarationPlanJournal,
    private val recovery: AddDeclarationRecoveryPreparationService,
    private val application: AddDeclarationApplicationService,
    private val verification: AddDeclarationVerificationService,
    private val transitions: WorkspaceTransitionPort,
) : NativeVerifiedAddDeclarationOperations {
    override suspend fun plan(
        request: VerifiedAddDeclarationPlanRequest,
    ): VerifiedAddDeclarationPlanResult {
        if (request.workspaceRoot.toJavaPath() != workspaceRoot) {
            return VerifiedAddDeclarationPlanResult.Rejected(
                VerifiedAddDeclarationPlanningFailure.WORKSPACE_MISMATCH,
            )
        }
        val target = Path.of(request.targetPath.value)
        val preimage = when (val read = SecureSourceProofRead.sha256(target)) {
            is SecureSourceProofReadOutcome.Read -> read.sha256
            is SecureSourceProofReadOutcome.Unavailable -> return VerifiedAddDeclarationPlanResult.Rejected(
                VerifiedAddDeclarationPlanningFailure.TARGET_UNAVAILABLE,
            )
        }
        val query = AddDeclarationPlanQuery(
            targetPath = AdditionTargetPath.parse(target.toString()),
            expectedCurrentSha256 = AdditionTargetPreimageSha256.of(preimage),
            proposedDeclaration = request.proposedDeclaration.value,
        ).parsed()
        val plan = try {
            backend.workspaceSemanticGate.current { lease ->
                backend.planAddDeclarationViaBinding(query, lease)
            }
        } catch (_: AdditionProofIncompleteException) {
            return VerifiedAddDeclarationPlanResult.Rejected(
                VerifiedAddDeclarationPlanningFailure.SEMANTIC_PLAN_REJECTED,
            )
        }
        return when (val stored = journal.store(plan)) {
            is StoreAddDeclarationPlanResult.Stored -> stored.record.toVerifiedPlanResult()
            is StoreAddDeclarationPlanResult.Existing -> when (val record = stored.record) {
                is PersistedAddDeclarationPlan.AwaitingApproval -> record.toVerifiedPlanResult()
                else -> VerifiedAddDeclarationPlanResult.Rejected(
                    VerifiedAddDeclarationPlanningFailure.JOURNAL_REJECTED,
                )
            }
            is StoreAddDeclarationPlanResult.Rejected -> VerifiedAddDeclarationPlanResult.Rejected(
                VerifiedAddDeclarationPlanningFailure.JOURNAL_REJECTED,
            )
        }
    }

    override suspend fun apply(
        request: VerifiedAddDeclarationApplyRequest,
    ): VerifiedAddDeclarationApplyResult {
        if (request.workspaceRoot.toJavaPath() != workspaceRoot) {
            return rejected(request.lifecycle(AddDeclarationPlanStage.AWAITING_APPROVAL),
                VerifiedAddDeclarationProgress.APPROVAL,
                VerifiedAddDeclarationRejection.WORKSPACE_MISMATCH)
        }
        val planId = request.changePlanId()
        val loaded = when (val result = journal.load(planId)) {
            is LoadAddDeclarationPlanResult.Found -> result.record
            is LoadAddDeclarationPlanResult.NotFound -> return rejected(
                request.lifecycle(AddDeclarationPlanStage.AWAITING_APPROVAL),
                VerifiedAddDeclarationProgress.APPROVAL,
                VerifiedAddDeclarationRejection.PLAN_NOT_FOUND,
            )
            is LoadAddDeclarationPlanResult.Rejected -> return reconciliation(
                request.lifecycle(AddDeclarationPlanStage.AWAITING_APPROVAL),
                VerifiedAddDeclarationProgress.APPROVAL,
                VerifiedAddDeclarationReconciliationAction.REFRESH_WORKSPACE,
            )
        }
        val awaiting = when (loaded) {
            is PersistedAddDeclarationPlan.AwaitingApproval -> loaded
            else -> return rejected(
                loaded.toWireLifecycle(),
                VerifiedAddDeclarationProgress.APPROVAL,
                VerifiedAddDeclarationRejection.PLAN_STATE_INVALID,
            )
        }
        if (request.expectedVersion.value != awaiting.version.value) {
            return rejected(
                awaiting.toWireLifecycle(),
                VerifiedAddDeclarationProgress.APPROVAL,
                VerifiedAddDeclarationRejection.STALE_PLAN_VERSION,
            )
        }
        val approved = when (val admission = approve(awaiting, request)) {
            is ApprovalAdmission.Admitted -> admission.approved
            ApprovalAdmission.Rejected -> return rejected(
                awaiting.toWireLifecycle(),
                VerifiedAddDeclarationProgress.APPROVAL,
                VerifiedAddDeclarationRejection.APPROVAL_REJECTED,
            )
        }
        val revalidated = when (val admission = revalidate(approved)) {
            is RevalidationAdmission.Admitted -> admission.revalidated
            RevalidationAdmission.Rejected -> return rejected(
                approved.toWireLifecycle(),
                VerifiedAddDeclarationProgress.REVALIDATION,
                VerifiedAddDeclarationRejection.REVALIDATION_REJECTED,
            )
        }
        val prepared = when (val result = recovery.prepare(approved, revalidated)) {
            is PrepareApprovedAddDeclarationRecoveryResult.Prepared -> result.recovery
            is PrepareApprovedAddDeclarationRecoveryResult.Rejected -> return rejected(
                approved.toWireLifecycle(),
                VerifiedAddDeclarationProgress.RECOVERY_PREPARATION,
                VerifiedAddDeclarationRejection.RECOVERY_PREPARATION_REJECTED,
            )
        }
        val capture = AtomicReference<ApplicationCapture>(ApplicationCapture.Pending)
        val mutation = transitions.mutate(
            signal = WorkspaceSignal.Source,
            detail = "verified add-declaration source application is active",
        ) {
            application.apply(prepared).also { result ->
                capture.set(ApplicationCapture.Completed(result))
            }
        }
        return when (mutation) {
            is WorkspaceMutationTransitionOutcome.Completed -> completeApply(
                mutation.value,
                prepared.record,
            )
            is WorkspaceMutationTransitionOutcome.Rejected -> when (val observed = capture.get()) {
                ApplicationCapture.Pending -> reconciliation(
                    prepared.record.toWireLifecycle(),
                    VerifiedAddDeclarationProgress.APPLY_ADMISSION,
                    VerifiedAddDeclarationReconciliationAction.REFRESH_WORKSPACE,
                )
                is ApplicationCapture.Completed -> reconcileCapturedApply(observed.result, prepared.record)
            }
        }
    }

    private fun approve(
        awaiting: PersistedAddDeclarationPlan.AwaitingApproval,
        request: VerifiedAddDeclarationApplyRequest,
    ): ApprovalAdmission {
        val evidence = when (val refinement = RawAddDeclarationPlanApprovalEvidence(
            planId = request.planId.value,
            approvedBy = request.approvalEvidence.approvedBy.value,
            evidenceSha256 = request.approvalEvidence.evidenceSha256.value,
        ).refine()) {
            is Refinement.Refined -> refinement.value
            is Refinement.Rejected -> return ApprovalAdmission.Rejected
        }
        val command = when (val refinement = ApproveAddDeclarationPlan.admit(
            planId = awaiting.plan.planId,
            expectedVersion = awaiting.version,
            evidence = evidence,
        )) {
            is Refinement.Refined -> refinement.value
            is Refinement.Rejected -> return ApprovalAdmission.Rejected
        }
        return when (val result = journal.approve(command)) {
            is ApproveAddDeclarationPlanResult.Approved -> ApprovalAdmission.Admitted(result.record)
            is ApproveAddDeclarationPlanResult.Rejected -> ApprovalAdmission.Rejected
        }
    }

    private suspend fun revalidate(
        approved: PersistedAddDeclarationPlan.Approved,
    ): RevalidationAdmission = try {
        backend.workspaceSemanticGate.current { lease ->
            val plan = approved.plan
            val query = AddDeclarationPlanQuery(
                targetPath = AdditionTargetPath.parse(plan.target.targetPath.value),
                expectedCurrentSha256 = AdditionTargetPreimageSha256.of(
                    plan.target.expectedCurrentSha256.value,
                ),
                proposedDeclaration = plan.intent.proposedDeclaration.value,
            ).parsed()
            val current = backend.planAddDeclarationViaBinding(query, lease)
            val observation = when (val refinement = AddDeclarationRevalidationObservation.observe(
                generation = lease.generation,
                target = current.target,
                currentFile = current.expectedFile.preimage,
                provenance = AddDeclarationSourceProvenance.AUTHORED,
                writability = AddDeclarationTargetWritability.WRITABLE,
            )) {
                is Refinement.Refined -> refinement.value
                is Refinement.Rejected -> return@current RevalidationAdmission.Rejected
            }
            when (val refinement = RevalidatedAddDeclaration.admit(plan, observation)) {
                is Refinement.Refined -> RevalidationAdmission.Admitted(refinement.value)
                is Refinement.Rejected -> RevalidationAdmission.Rejected
            }
        }
    } catch (_: AdditionProofIncompleteException) {
        RevalidationAdmission.Rejected
    }

    private suspend fun completeApply(
        result: ApplyRecoveryPreparedAddDeclarationResult,
        fallback: PersistedAddDeclarationPlan,
    ): VerifiedAddDeclarationApplyResult = when (result) {
        is ApplyRecoveryPreparedAddDeclarationResult.AppliedUnverified ->
            completeVerification(verification.verify(result.record))
        is ApplyRecoveryPreparedAddDeclarationResult.RejectedBeforeAdmission -> rejected(
            fallback.toWireLifecycle(),
            VerifiedAddDeclarationProgress.APPLY_ADMISSION,
            VerifiedAddDeclarationRejection.APPLY_REJECTED,
        )
        is ApplyRecoveryPreparedAddDeclarationResult.ApplyAdmissionReconciliationRequired -> reconciliation(
            result.recoveryPrepared.record.toWireLifecycle(),
            VerifiedAddDeclarationProgress.APPLY_ADMISSION,
            VerifiedAddDeclarationReconciliationAction.REFRESH_WORKSPACE,
        )
        is ApplyRecoveryPreparedAddDeclarationResult.RecoveryRequiredBeforeMutation -> recoveryRequired(
            result.admitted.toWireLifecycle(),
            VerifiedAddDeclarationProgress.SOURCE_APPLICATION,
            VerifiedAddDeclarationRecoveryAction.RESTORE_PREIMAGE,
        )
        is ApplyRecoveryPreparedAddDeclarationResult.RecoveryRequiredAfterMutation -> recoveryRequired(
            result.admitted.toWireLifecycle(),
            VerifiedAddDeclarationProgress.SOURCE_APPLICATION,
            VerifiedAddDeclarationRecoveryAction.RESTORE_PREIMAGE,
        )
        is ApplyRecoveryPreparedAddDeclarationResult.RecoveryRequiredMutationOutcomeUnknown -> recoveryRequired(
            result.admitted.toWireLifecycle(),
            VerifiedAddDeclarationProgress.SOURCE_APPLICATION,
            VerifiedAddDeclarationRecoveryAction.RESTORE_PREIMAGE,
        )
        is ApplyRecoveryPreparedAddDeclarationResult.CompletionReconciliationRequired -> reconciliation(
            result.admitted.toWireLifecycle(),
            VerifiedAddDeclarationProgress.SOURCE_APPLICATION,
            VerifiedAddDeclarationReconciliationAction.REFRESH_WORKSPACE,
        )
    }

    private fun reconcileCapturedApply(
        result: ApplyRecoveryPreparedAddDeclarationResult,
        fallback: PersistedAddDeclarationPlan,
    ): VerifiedAddDeclarationApplyResult = reconciliation(
        result.strongestLifecycleOr(fallback),
        VerifiedAddDeclarationProgress.WORKSPACE_PUBLICATION,
        VerifiedAddDeclarationReconciliationAction.REFRESH_WORKSPACE,
    )

    private fun completeVerification(
        result: VerifyAppliedAddDeclarationResult,
    ): VerifiedAddDeclarationApplyResult = when (result) {
        is VerifyAppliedAddDeclarationResult.Verified -> result.record.toWireVerified()
        is VerifyAppliedAddDeclarationResult.RejectedBeforePublication -> reconciliation(
            result.applied.toWireLifecycle(),
            VerifiedAddDeclarationProgress.WORKSPACE_PUBLICATION,
            VerifiedAddDeclarationReconciliationAction.RETRY_PUBLICATION,
        )
        is VerifyAppliedAddDeclarationResult.RejectedAfterPublication -> reconciliation(
            result.applied.toWireLifecycle(),
            VerifiedAddDeclarationProgress.POSTCONDITION_VERIFICATION,
            VerifiedAddDeclarationReconciliationAction.RETRY_VERIFICATION,
        )
        is VerifyAppliedAddDeclarationResult.RejectedAfterVerification -> reconciliation(
            result.applied.toWireLifecycle(),
            VerifiedAddDeclarationProgress.POSTCONDITION_VERIFICATION,
            VerifiedAddDeclarationReconciliationAction.RETRY_VERIFICATION,
        )
        is VerifyAppliedAddDeclarationResult.CompletionReconciliationRequired -> reconciliation(
            result.applied.toWireLifecycle(),
            VerifiedAddDeclarationProgress.POSTCONDITION_VERIFICATION,
            VerifiedAddDeclarationReconciliationAction.RETRY_VERIFICATION,
        )
    }
}

private sealed interface ApplicationCapture {
    data object Pending : ApplicationCapture

    data class Completed(
        val result: ApplyRecoveryPreparedAddDeclarationResult,
    ) : ApplicationCapture
}

private sealed interface ApprovalAdmission {
    data class Admitted(
        val approved: PersistedAddDeclarationPlan.Approved,
    ) : ApprovalAdmission

    data object Rejected : ApprovalAdmission
}

private sealed interface RevalidationAdmission {
    data class Admitted(
        val revalidated: RevalidatedAddDeclaration,
    ) : RevalidationAdmission

    data object Rejected : RevalidationAdmission
}
