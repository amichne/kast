package io.github.amichne.kast.idea.backend.mutation.operations

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.api.contract.CreateFileParentPolicy
import io.github.amichne.kast.api.contract.FileOperation
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.query.MutationPostconditionAuthority
import io.github.amichne.kast.api.contract.query.MutationPostconditionQuery
import io.github.amichne.kast.api.contract.query.RefreshQuery
import io.github.amichne.kast.api.contract.result.MutationPostconditionEvidence
import io.github.amichne.kast.api.contract.result.SemanticAnalysisOutcome
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.server.change.ApprovedVerifiedAddFilePlan
import io.github.amichne.kast.server.change.NativeVerifiedAddFileOperations
import io.github.amichne.kast.server.change.VerifiedAddFileAdmission
import io.github.amichne.kast.server.change.VerifiedAddFileApprovalChallenge
import io.github.amichne.kast.server.change.RevalidatedVerifiedAddFilePlan
import io.github.amichne.kast.server.change.VerifiedAddFileApplyRequest
import io.github.amichne.kast.server.change.VerifiedAddFileApplyResult
import io.github.amichne.kast.server.change.VerifiedAddFileFailure
import io.github.amichne.kast.server.change.VerifiedAddFileIntent
import io.github.amichne.kast.server.change.VerifiedAddFilePlan
import io.github.amichne.kast.server.change.VerifiedAddFilePlanId
import io.github.amichne.kast.server.change.VerifiedAddFilePlanPreview
import io.github.amichne.kast.server.change.VerifiedAddFilePlanRequest
import io.github.amichne.kast.server.change.VerifiedAddFilePlanResult
import io.github.amichne.kast.server.change.VerifiedAddFilePlanStage
import io.github.amichne.kast.server.change.VerifiedAddFilePlanVersion
import io.github.amichne.kast.server.change.VerifiedAddFileProgress
import io.github.amichne.kast.server.change.VerifiedAddFileReceipt
import io.github.amichne.kast.server.change.VerifiedAddFileRecoveryDisposition
import io.github.amichne.kast.server.change.VerifiedAddFileRecoveryDispositionAction
import io.github.amichne.kast.server.change.VerifiedAddFileRefinement
import java.security.MessageDigest
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Proof transition: (KastIndexerBackend, Path) -> NativeVerifiedAddFileOperations.
 *
 * Establishes the sole KIP-050 composition of exact addition planning, target-absence revalidation,
 * recovery preparation, one authored Kotlin file creation, workspace publication, and PSI/K2
 * verification. Expected failures remain [VerifiedAddFileFailure]; raw paths and content are
 * extracted only at their filesystem and IntelliJ adapter boundaries.
 */
internal fun KastIndexerBackend.verifiedAddFileOperations(
    workspaceRoot: Path,
): NativeVerifiedAddFileOperations = IntellijVerifiedAddFileOperations(
    backend = this,
    workspaceRoot = workspaceRoot.toAbsolutePath().normalize(),
)

private class IntellijVerifiedAddFileOperations(
    private val backend: KastIndexerBackend,
    private val workspaceRoot: Path,
) : NativeVerifiedAddFileOperations {
    private val plans = ConcurrentHashMap<String, PersistedVerifiedAddFilePlan>()

    override suspend fun plan(request: VerifiedAddFilePlanRequest): VerifiedAddFilePlanResult {
        if (request.workspaceRoot.toJavaPath() != workspaceRoot) {
            return VerifiedAddFilePlanResult.Rejected(VerifiedAddFileFailure.WORKSPACE_MISMATCH)
        }
        val intent = VerifiedAddFileIntent(
            workspaceRoot = request.workspaceRoot,
            targetPath = request.targetPath,
            content = request.proposedContent,
        )
        when (val admission = admitVerifiedAddFileTarget(workspaceRoot, intent)) {
            TargetAdmission.Admitted -> Unit
            is TargetAdmission.Rejected -> return VerifiedAddFilePlanResult.Rejected(admission.failure)
        }
        val attempted = planVerifiedAddFile(backend, intent, VerifiedAddFileProgress.PLANNING)
        if (attempted is PlanAttempt.Rejected) {
            return VerifiedAddFilePlanResult.Rejected(attempted.result.failure)
        }
        val planned = (attempted as PlanAttempt.Planned).plan
        val planId = verifiedAddFilePlanId(planned)
        val version = wireVersion(INITIAL_PLAN_VERSION)
        val persisted = PersistedVerifiedAddFilePlan(
            planId = planId,
            initialVersion = version,
            planned = planned,
        )
        val authoritative = plans.putIfAbsent(planId.value, persisted) ?: persisted
        return authoritative.toWirePlan()
    }

    override suspend fun apply(request: VerifiedAddFileApplyRequest): VerifiedAddFileApplyResult {
        if (request.workspaceRoot.toJavaPath() != workspaceRoot) {
            return applyRejected(
                request,
                VerifiedAddFileProgress.INTENT_ADMISSION,
                VerifiedAddFileFailure.WORKSPACE_MISMATCH,
            )
        }
        val persisted = plans[request.planId.value]
            ?: return applyRejected(
                request,
                VerifiedAddFileProgress.INTENT_ADMISSION,
                VerifiedAddFileFailure.PLAN_NOT_FOUND,
            )
        return persisted.gate.withLock {
            val lifecycle = persisted.lifecycle
            if (request.expectedVersion != persisted.initialVersion) {
                return@withLock applyRejected(
                    request,
                    VerifiedAddFileProgress.REVALIDATION,
                    VerifiedAddFileFailure.STALE_PLAN_VERSION,
                )
            }
            val approved = when (
                val admission = ApprovedVerifiedAddFilePlan.admit(
                    challenge = persisted.approvalChallenge,
                    evidence = request.approvalEvidence,
                )
            ) {
                is VerifiedAddFileAdmission.Admitted -> admission.value
                is VerifiedAddFileAdmission.Rejected -> return@withLock applyRejected(
                    request,
                    VerifiedAddFileProgress.REVALIDATION,
                    admission.failure,
                )
            }
            when (lifecycle) {
                is PersistedVerifiedAddFileLifecycle.Terminal.Verified ->
                    return@withLock lifecycle.result
                is PersistedVerifiedAddFileLifecycle.Terminal.RolledBack ->
                    return@withLock lifecycle.result
                is PersistedVerifiedAddFileLifecycle.NonDestructiveReconciliationRequired ->
                    return@withLock lifecycle.result
                PersistedVerifiedAddFileLifecycle.AwaitingApproval,
                is PersistedVerifiedAddFileLifecycle.RecoveryRequired,
                is PersistedVerifiedAddFileLifecycle.ReconciliationRequired,
                -> Unit
            }
            val result = when (lifecycle) {
                is PersistedVerifiedAddFileLifecycle.RecoveryRequired -> recoverVerifiedAddFileFailure(
                    backend,
                    lifecycle.application,
                    lifecycle.progress,
                    lifecycle.failure,
                )
                is PersistedVerifiedAddFileLifecycle.ReconciliationRequired -> recoverVerifiedAddFileFailure(
                    backend,
                    lifecycle.application,
                    lifecycle.progress,
                    lifecycle.failure,
                )
                PersistedVerifiedAddFileLifecycle.AwaitingApproval -> applyApprovedPlan(approved)
                is PersistedVerifiedAddFileLifecycle.NonDestructiveReconciliationRequired ->
                    error("non-destructive reconciliation replay returned above")
                is PersistedVerifiedAddFileLifecycle.Terminal -> error("terminal replay returned above")
            }
            when (result) {
                is VerifiedAddFileResult.Verified -> VerifiedAddFileApplyResult.Verified(
                    planId = persisted.planId,
                    planVersion = wireVersion(TERMINAL_PLAN_VERSION),
                    receipt = result.receipt,
                ).also {
                    persisted.lifecycle = PersistedVerifiedAddFileLifecycle.Terminal.Verified(it)
                }
                is VerifiedAddFileResult.Rejected -> VerifiedAddFileApplyResult.Rejected(
                    planId = persisted.planId,
                    planVersion = persisted.initialVersion,
                    stage = result.progress.toStage(),
                    progress = result.progress,
                    failure = result.failure,
                )
                is VerifiedAddFileResult.RolledBack -> VerifiedAddFileApplyResult.RolledBack(
                    planId = persisted.planId,
                    planVersion = wireVersion(TERMINAL_PLAN_VERSION),
                    stage = result.progress.toStage(),
                    progress = result.progress,
                    failure = result.failure,
                    action = result.action,
                ).also {
                    persisted.lifecycle = PersistedVerifiedAddFileLifecycle.Terminal.RolledBack(it)
                }
                is VerifiedAddFileResult.RecoveryRequired ->
                    VerifiedAddFileApplyResult.RecoveryRequired(
                        planId = persisted.planId,
                        recoveryId = result.application.recovery.recoveryId,
                        planVersion = persisted.initialVersion,
                        stage = result.progress.toStage(),
                        progress = result.progress,
                        failure = result.failure,
                        action = result.action,
                    ).also {
                        persisted.lifecycle = PersistedVerifiedAddFileLifecycle.RecoveryRequired(
                            application = result.application,
                            progress = result.progress,
                            failure = result.failure,
                            action = result.action,
                        )
                    }
                is VerifiedAddFileResult.ReconciliationRequired ->
                    VerifiedAddFileApplyResult.ReconciliationRequired(
                        planId = persisted.planId,
                        recoveryId = result.application.recovery.recoveryId,
                        planVersion = persisted.initialVersion,
                        stage = result.progress.toStage(),
                        progress = result.progress,
                        failure = result.failure,
                        action = result.action,
                    ).also {
                        persisted.lifecycle = PersistedVerifiedAddFileLifecycle.ReconciliationRequired(
                            application = result.application,
                            progress = result.progress,
                            failure = result.failure,
                            action = result.action,
                        )
                    }
                is VerifiedAddFileResult.NonDestructiveReconciliationRequired ->
                    VerifiedAddFileApplyResult.ReconciliationRequired(
                        planId = persisted.planId,
                        recoveryId = result.recoveryId,
                        planVersion = persisted.initialVersion,
                        stage = result.progress.toStage(),
                        progress = result.progress,
                        failure = result.failure,
                        action = result.action,
                    ).also {
                        persisted.lifecycle =
                            PersistedVerifiedAddFileLifecycle.NonDestructiveReconciliationRequired(it)
                    }
            }
        }
    }

    private suspend fun applyApprovedPlan(
        approved: ApprovedVerifiedAddFilePlan,
    ): VerifiedAddFileResult = applyPlanned(approved.planned)

    private suspend fun applyPlanned(
        exactPlan: VerifiedAddFilePlan,
    ): VerifiedAddFileResult {
        val intent = exactPlan.intent
        val replanned = planVerifiedAddFile(backend, intent, VerifiedAddFileProgress.REVALIDATION)
        if (replanned !is PlanAttempt.Planned) {
            return rejected(
                VerifiedAddFileProgress.REVALIDATION,
                VerifiedAddFileFailure.PLAN_REVALIDATION_FAILED,
            )
        }
        val revalidated = when (
            val admission = RevalidatedVerifiedAddFilePlan.admit(exactPlan, replanned.plan.exact)
        ) {
            is VerifiedAddFileAdmission.Admitted -> admission.value
            is VerifiedAddFileAdmission.Rejected -> return rejected(
                VerifiedAddFileProgress.REVALIDATION,
                admission.failure,
            )
        }
        val recovery = when (
            val admission = prepareVerifiedAddFileRecovery(workspaceRoot, revalidated)
        ) {
            is VerifiedAddFileProofAdmission.Admitted -> admission.value
            is VerifiedAddFileProofAdmission.Rejected -> return rejected(
                VerifiedAddFileProgress.RECOVERY_PREPARATION,
                admission.failure,
            )
        }
        val writeAuthorization = when (
            val admission = VerifiedAddFileVcsWriteAuthorized.admit(recovery)
        ) {
            is VerifiedAddFileAdmission.Admitted -> admission.value
            is VerifiedAddFileAdmission.Rejected -> return rejected(
                VerifiedAddFileProgress.SOURCE_APPLICATION,
                admission.failure,
            )
        }
        val application = when (val sourceApplication = writeAuthorization.applyPlannedTarget(backend)) {
            is VerifiedAddFileSourceApplication.Applied -> sourceApplication.application
            is VerifiedAddFileSourceApplication.AppliedFailure -> return recoverVerifiedAddFileFailure(
                backend,
                sourceApplication.application,
                VerifiedAddFileProgress.SOURCE_APPLICATION,
                sourceApplication.failure,
            )
            is VerifiedAddFileSourceApplication.CommitUnproven -> return sourceApplication.toResult()
        }
        val refresh = try {
            backend.refresh(RefreshQuery(filePaths = listOf(intent.targetPath.value)).parsed())
        } catch (_: ProcessCanceledException) {
            return VerifiedAddFileResult.RecoveryRequired(
                application,
                VerifiedAddFileProgress.WORKSPACE_PUBLICATION,
                VerifiedAddFileFailure.CANCELLED,
                VerifiedAddFileRecoveryDispositionAction.DELETE_CREATED_TARGET,
            )
        } catch (_: CancellationException) {
            return VerifiedAddFileResult.RecoveryRequired(
                application,
                VerifiedAddFileProgress.WORKSPACE_PUBLICATION,
                VerifiedAddFileFailure.CANCELLED,
                VerifiedAddFileRecoveryDispositionAction.DELETE_CREATED_TARGET,
            )
        } catch (_: Exception) {
            return recoverVerifiedAddFileFailure(
                backend,
                application,
                VerifiedAddFileProgress.WORKSPACE_PUBLICATION,
                VerifiedAddFileFailure.PUBLICATION_FAILED,
            )
        }
        if (refresh.semanticOutcome != SemanticAnalysisOutcome.COMPLETE) {
            return recoverVerifiedAddFileFailure(
                backend,
                application,
                VerifiedAddFileProgress.WORKSPACE_PUBLICATION,
                VerifiedAddFileFailure.PUBLICATION_FAILED,
            )
        }
        val verified = try {
            backend.verifyMutationPostcondition(
                MutationPostconditionQuery(
                    MutationPostconditionAuthority.AddFile(exactPlan.exact.proof, exactPlan.exact.postimage),
                ).parsed(),
            )
        } catch (_: ProcessCanceledException) {
            return VerifiedAddFileResult.RecoveryRequired(
                application,
                VerifiedAddFileProgress.PSI_ADMISSION,
                VerifiedAddFileFailure.CANCELLED,
                VerifiedAddFileRecoveryDispositionAction.DELETE_CREATED_TARGET,
            )
        } catch (_: CancellationException) {
            return VerifiedAddFileResult.RecoveryRequired(
                application,
                VerifiedAddFileProgress.PSI_ADMISSION,
                VerifiedAddFileFailure.CANCELLED,
                VerifiedAddFileRecoveryDispositionAction.DELETE_CREATED_TARGET,
            )
        } catch (_: Exception) {
            return recoverVerifiedAddFileFailure(
                backend,
                application,
                VerifiedAddFileProgress.PSI_ADMISSION,
                VerifiedAddFileFailure.PSI_NOT_ADMITTED,
            )
        }
        val evidence = verified.evidence as? MutationPostconditionEvidence.AddFile ?: run {
            return recoverVerifiedAddFileFailure(
                backend,
                application,
                VerifiedAddFileProgress.PSI_ADMISSION,
                VerifiedAddFileFailure.PSI_NOT_ADMITTED,
            )
        }
        val transition = when (
            val admission = VerifiedAddFileTransition.admit(application, verified.currentGeneration)
        ) {
            is VerifiedAddFileProofAdmission.Admitted -> admission.value
            is VerifiedAddFileProofAdmission.Rejected -> return recoverVerifiedAddFileFailure(
                backend,
                application,
                VerifiedAddFileProgress.PSI_ADMISSION,
                admission.failure,
            )
        }
        val verification = when (val admission = VerifiedAddFileVerification.admit(transition, evidence)) {
            is VerifiedAddFileProofAdmission.Admitted -> admission.value
            is VerifiedAddFileProofAdmission.Rejected -> return recoverVerifiedAddFileFailure(
                backend,
                application,
                VerifiedAddFileProgress.PSI_ADMISSION,
                admission.failure,
            )
        }
        return VerifiedAddFileResult.Verified(verification.toReceipt())
    }

}


private const val INITIAL_PLAN_VERSION = 0L
private const val TERMINAL_PLAN_VERSION = 5L
