package io.github.amichne.kast.idea.backend.mutation.operations

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.api.contract.FileOperation
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.query.RefreshQuery
import io.github.amichne.kast.api.contract.result.SemanticAnalysisOutcome
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.PartialApplyException
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.server.change.AdmittedVerifiedAddFileApplyResult
import io.github.amichne.kast.server.change.RevalidatedVerifiedAddFilePlan
import io.github.amichne.kast.server.change.VerifiedAddFileApplyResult
import io.github.amichne.kast.server.change.VerifiedAddFileApplyResultAdmission
import io.github.amichne.kast.server.change.VerifiedAddFileFailure
import io.github.amichne.kast.server.change.VerifiedAddFilePlanId
import io.github.amichne.kast.server.change.VerifiedAddFilePlanVersion
import io.github.amichne.kast.server.change.VerifiedAddFileReconciliationAction
import io.github.amichne.kast.server.change.VerifiedAddFileRecoveryDisposition
import io.github.amichne.kast.server.change.VerifiedAddFileRecoveryDispositionAction
import io.github.amichne.kast.server.change.VerifiedAddFileRecoveryId
import io.github.amichne.kast.server.change.VerifiedAddFileRefinement
import io.github.amichne.kast.server.change.VerifiedAddFileProgress
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.CancellationException

/**
 * Proof transition:
 * `(Path, RevalidatedVerifiedAddFilePlan)`
 * to `VerifiedAddFileAdmission<VerifiedAddFileRecoveryPrepared>`.
 *
 * Establishes exact-target absence, an existing canonical contained parent, and parent writability
 * before source application. Closed expected failures are [VerifiedAddFileFailure.TARGET_ALREADY_EXISTS],
 * [VerifiedAddFileFailure.TARGET_NOT_WRITABLE], and [VerifiedAddFileFailure.TARGET_SYMLINK_ESCAPE].
 * Raw paths are extracted only at this recovery filesystem boundary.
 */
internal fun prepareVerifiedAddFileRecovery(
    workspaceRoot: Path,
    revalidated: RevalidatedVerifiedAddFilePlan,
): VerifiedAddFileProofAdmission<VerifiedAddFileRecoveryPrepared> =
    VerifiedAddFileRecoveryPrepared.admit(
        workspaceRoot,
        revalidated,
        verifiedAddFileRecoveryId(revalidated.planned),
    )

internal sealed interface VerifiedAddFileUnknownOutcomeAdmission {
    data object RevalidateAbsentTarget : VerifiedAddFileUnknownOutcomeAdmission
    data class Reconcile(
        val observation: VerifiedAddFileNonDestructiveObservation,
    ) : VerifiedAddFileUnknownOutcomeAdmission
}

/**
 * Proof transition: `ApplyOutcomeUnknown -> VerifiedAddFileUnknownOutcomeAdmission`.
 *
 * An exact absent target permits semantic replanning before source application. A present or
 * unprovable target retains only non-destructive reconciliation authority. Raw target status is
 * extracted only at this recovery filesystem boundary.
 */
internal fun PersistedVerifiedAddFileLifecycle.ApplyOutcomeUnknown.admitResume():
    VerifiedAddFileUnknownOutcomeAdmission {
    val target = Path.of(recovery.plan.planned.intent.targetPath.value)
    return when {
        Files.notExists(target, NOFOLLOW_LINKS) ->
            VerifiedAddFileUnknownOutcomeAdmission.RevalidateAbsentTarget
        Files.exists(target, NOFOLLOW_LINKS) -> VerifiedAddFileUnknownOutcomeAdmission.Reconcile(
            VerifiedAddFileNonDestructiveObservation.TARGET_OBSERVATION_ALLOWED,
        )
        else -> VerifiedAddFileUnknownOutcomeAdmission.Reconcile(
            VerifiedAddFileNonDestructiveObservation.COMMIT_EVIDENCE_INCOMPLETE,
        )
    }
}

/**
 * Effect transition:
 * `AppliedVerifiedAddFile -> VerifiedAddFileRecoveryDisposition`.
 *
 * Establishes either the exact absent preimage, a retained exact postimage needing deletion, or
 * an ambiguous target requiring reconciliation. No recovery result is discarded. Raw bytes and
 * filesystem status are extracted only at this recovery boundary.
 */
internal suspend fun recoverVerifiedAddFileTarget(
    backend: KastIndexerBackend,
    application: AppliedVerifiedAddFile,
): VerifiedAddFileRecoveryDisposition {
    val target = Path.of(application.targetPath.value)
    if (Files.notExists(target, NOFOLLOW_LINKS)) {
        return verifyPublishedVerifiedAddFileAbsence(
            backend,
            VerifiedAddFilePublishedAbsenceCandidate.ObservedUnderRetainedAuthority(application),
        )
    }
    return try {
        val applied = backend.applyEdits(
            ApplyEditsQuery(
                edits = emptyList(),
                fileHashes = emptyList(),
                fileOperations = listOf(
                    FileOperation.DeleteFile(
                        filePath = target.toString(),
                        expectedHash = application.postimageSha256.value,
                    ),
                ),
            ).parsed(),
        )
        if (applied.deletedFiles == listOf(target.toString()) && Files.notExists(target, NOFOLLOW_LINKS)) {
            VerifiedAddFileRecoveryDisposition.RolledBack
        } else {
            VerifiedAddFileRecoveryDisposition.RecoveryRequired(
                VerifiedAddFileRecoveryDispositionAction.DELETE_CREATED_TARGET,
            )
        }
    } catch (_: ProcessCanceledException) {
        VerifiedAddFileRecoveryDisposition.Cancelled
    } catch (_: CancellationException) {
        VerifiedAddFileRecoveryDisposition.Cancelled
    } catch (_: ConflictException) {
        VerifiedAddFileRecoveryDisposition.ReconciliationRequired(
            VerifiedAddFileReconciliationAction.INSPECT_TARGET,
        )
    } catch (failure: PartialApplyException) {
        when (val admission = admitCommittedVerifiedAddFileDelete(failure, target)) {
            VerifiedAddFileCommittedDeleteAdmission.Unproven ->
                VerifiedAddFileRecoveryDisposition.RecoveryRequired(
                    VerifiedAddFileRecoveryDispositionAction.DELETE_CREATED_TARGET,
                )
            is VerifiedAddFileCommittedDeleteAdmission.Admitted ->
                verifyPublishedVerifiedAddFileAbsence(backend, admission)
        }
    } catch (_: Exception) {
        VerifiedAddFileRecoveryDisposition.RecoveryRequired(
            VerifiedAddFileRecoveryDispositionAction.DELETE_CREATED_TARGET,
        )
    }
}

private sealed interface VerifiedAddFileCommittedDeleteAdmission {
    data object Unproven : VerifiedAddFileCommittedDeleteAdmission

    data class Admitted(
        override val target: Path,
    ) : VerifiedAddFileCommittedDeleteAdmission, VerifiedAddFilePublishedAbsenceCandidate
}

private sealed interface VerifiedAddFilePublishedAbsenceCandidate {
    val target: Path

    data class ObservedUnderRetainedAuthority(
        val application: AppliedVerifiedAddFile,
    ) : VerifiedAddFilePublishedAbsenceCandidate {
        override val target: Path = Path.of(application.targetPath.value)
    }

    data class ObservedUnderNonDestructiveAuthority(
        val recovery: VerifiedAddFileRecoveryPrepared,
    ) : VerifiedAddFilePublishedAbsenceCandidate {
        override val target: Path = Path.of(recovery.plan.planned.intent.targetPath.value)
    }
}

/**
 * Proof transition:
 * `(PartialApplyException, Path) -> VerifiedAddFileCommittedDeleteAdmission`.
 *
 * Admitted proves that the fenced exact-image delete committed for the one requested target and
 * retained no secure-mutation recovery artifact. Unproven is the closed failure when either claim
 * is absent. Raw exception detail extraction is permitted only at this partial-apply boundary.
 */
private fun admitCommittedVerifiedAddFileDelete(
    failure: PartialApplyException,
    target: Path,
): VerifiedAddFileCommittedDeleteAdmission {
    val committedTarget = failure.details["deletedFiles"]
    val retainedRecoveryPath = failure.details.keys.any { key ->
        key == "recoveryFilePathCount" ||
            key == "recoveryFilePath" ||
            key.startsWith("recoveryFilePath.")
    }
    return if (committedTarget == target.toString() && !retainedRecoveryPath) {
        VerifiedAddFileCommittedDeleteAdmission.Admitted(target)
    } else {
        VerifiedAddFileCommittedDeleteAdmission.Unproven
    }
}

/**
 * Proof transition:
 * `VerifiedAddFilePublishedAbsenceCandidate -> VerifiedAddFileRecoveryDisposition`.
 *
 * Accepts a structured committed exact delete, absence under artifact-free
 * [AppliedVerifiedAddFile] authority, or absence under the original non-destructive recovery
 * capability. RolledBack is emitted only after a focused refresh returns the exact target as
 * removed with a complete semantic outcome. Any incomplete publication retains recovery
 * authority. Raw target paths are extracted only at this refresh boundary.
 */
private suspend fun verifyPublishedVerifiedAddFileAbsence(
    backend: KastIndexerBackend,
    candidate: VerifiedAddFilePublishedAbsenceCandidate,
): VerifiedAddFileRecoveryDisposition = try {
    val target = candidate.target.toString()
    val refreshed = backend.refresh(RefreshQuery(filePaths = listOf(target)).parsed())
    if (
        refreshed.removedFiles == listOf(target) &&
        refreshed.semanticOutcome == SemanticAnalysisOutcome.COMPLETE
    ) {
        VerifiedAddFileRecoveryDisposition.RolledBack
    } else {
        VerifiedAddFileRecoveryDisposition.RecoveryRequired(
            VerifiedAddFileRecoveryDispositionAction.DELETE_CREATED_TARGET,
        )
    }
} catch (_: ProcessCanceledException) {
    VerifiedAddFileRecoveryDisposition.Cancelled
} catch (_: CancellationException) {
    VerifiedAddFileRecoveryDisposition.Cancelled
} catch (_: Exception) {
    VerifiedAddFileRecoveryDisposition.RecoveryRequired(
        VerifiedAddFileRecoveryDispositionAction.DELETE_CREATED_TARGET,
    )
}

/**
 * Effect transition: `(VerifiedAddFileRecoveryPrepared, VerifiedAddFileProgress,
 * VerifiedAddFileFailure, VerifiedAddFileReconciliationAction,
 * VerifiedAddFileNonDestructiveObservation) -> VerifiedAddFileResult`.
 *
 * Re-observes an unproven source application without acquiring delete authority. An absent target
 * reaches rollback only when the structured conflict admits target observation and exact focused
 * publication proves removal. Incomplete commit evidence, a present target, cancellation, or
 * incomplete publication preserves the same non-destructive reconciliation capability. Raw
 * filesystem absence is observed only at this recovery boundary.
 */
internal suspend fun reconcileUnprovenVerifiedAddFileFailure(
    backend: KastIndexerBackend,
    recovery: VerifiedAddFileRecoveryPrepared,
    progress: VerifiedAddFileProgress,
    failure: VerifiedAddFileFailure,
    action: VerifiedAddFileReconciliationAction,
    observation: VerifiedAddFileNonDestructiveObservation,
): VerifiedAddFileResult {
    if (observation != VerifiedAddFileNonDestructiveObservation.TARGET_OBSERVATION_ALLOWED) {
        return VerifiedAddFileResult.NonDestructiveReconciliationRequired(
            recovery,
            progress,
            failure,
            action,
            observation,
        )
    }
    val target = Path.of(recovery.plan.planned.intent.targetPath.value)
    if (!Files.notExists(target, NOFOLLOW_LINKS)) {
        return VerifiedAddFileResult.NonDestructiveReconciliationRequired(
            recovery,
            progress,
            failure,
            action,
            observation,
        )
    }
    return when (
        verifyPublishedVerifiedAddFileAbsence(
            backend,
            VerifiedAddFilePublishedAbsenceCandidate.ObservedUnderNonDestructiveAuthority(recovery),
        )
    ) {
        VerifiedAddFileRecoveryDisposition.RolledBack -> VerifiedAddFileResult.RolledBack(
            progress,
            failure,
            VerifiedAddFileRecoveryDispositionAction.DELETE_CREATED_TARGET,
        )
        else -> VerifiedAddFileResult.NonDestructiveReconciliationRequired(
            recovery,
            progress,
            failure,
            action,
            observation,
        )
    }
}

internal suspend fun PersistedVerifiedAddFileLifecycle.NonDestructiveReconciliationRequired.reconcile(
    backend: KastIndexerBackend,
): VerifiedAddFileResult = reconcileUnprovenVerifiedAddFileFailure(
    backend,
    recovery,
    progress,
    failure,
    action,
    observation,
)

internal class VerifiedAddFileNonDestructivePersistenceTransition private constructor(
    val wireResult: VerifiedAddFileApplyResult.ReconciliationRequired,
    val lifecycle: PersistedVerifiedAddFileLifecycle.NonDestructiveReconciliationRequired,
) {
    companion object {
        /**
         * Proof transition: `(VerifiedAddFileResult.NonDestructiveReconciliationRequired,
         * VerifiedAddFilePlanId, VerifiedAddFilePlanVersion)` to
         * `VerifiedAddFileNonDestructivePersistenceTransition`.
         *
         * Preserves the exact strong recovery capability in both the public wire result and its
         * durable non-destructive lifecycle. Raw serialization is permitted only after this
         * projection at the journal and RPC boundaries.
         */
        fun from(
            result: VerifiedAddFileResult.NonDestructiveReconciliationRequired,
            planId: VerifiedAddFilePlanId,
            planVersion: VerifiedAddFilePlanVersion,
        ): VerifiedAddFileNonDestructivePersistenceTransition =
            VerifiedAddFileNonDestructivePersistenceTransition(
                wireResult = VerifiedAddFileApplyResult.ReconciliationRequired(
                    planId = planId,
                    recoveryId = result.recovery.recoveryId,
                    planVersion = planVersion,
                    stage = result.progress.toStage(),
                    progress = result.progress,
                    failure = result.failure,
                    action = result.action,
                ),
                lifecycle = PersistedVerifiedAddFileLifecycle.NonDestructiveReconciliationRequired(
                    result.recovery,
                    result.progress,
                    result.failure,
                    result.action,
                    result.observation,
                ),
            )
    }
}

internal fun VerifiedAddFileResult.NonDestructiveReconciliationRequired.toPersistenceTransition(
    planId: VerifiedAddFilePlanId,
    planVersion: VerifiedAddFilePlanVersion,
): VerifiedAddFileNonDestructivePersistenceTransition =
    VerifiedAddFileNonDestructivePersistenceTransition.from(this, planId, planVersion)

/** Proof transition: wire result to closed lifecycle admission through the finite result matrix. */
internal fun admittedLifecycle(
    candidate: VerifiedAddFileApplyResult,
    lifecycle: (VerifiedAddFileApplyResult) -> PersistedVerifiedAddFileLifecycle,
): DurableLifecycleAdmission = when (val result = AdmittedVerifiedAddFileApplyResult.admit(candidate)) {
    is VerifiedAddFileApplyResultAdmission.Admitted ->
        DurableLifecycleAdmission.Admitted(lifecycle(result.value.result))
    is VerifiedAddFileApplyResultAdmission.Rejected -> DurableLifecycleAdmission.Rejected
}

/** Proof transition: raw journal string to typed recovery identity or closed rejection. */
internal fun refineRecoveryId(raw: String): DurableRecoveryIdAdmission =
    when (val result = VerifiedAddFileRecoveryId.refine(raw)) {
        is VerifiedAddFileRefinement.Refined -> DurableRecoveryIdAdmission.Admitted(result.value)
        is VerifiedAddFileRefinement.Rejected -> DurableRecoveryIdAdmission.Rejected
    }

/**
 * Effect transition: (AppliedVerifiedAddFile, VerifiedAddFileProgress,
 * VerifiedAddFileFailure) to VerifiedAddFileResult.
 *
 * Consumes the retained recovery capability and preserves the original failure together with the
 * strongest resulting disposition. It never replans against a possibly present target.
 */
internal suspend fun recoverVerifiedAddFileFailure(
    backend: KastIndexerBackend,
    application: AppliedVerifiedAddFile,
    progress: VerifiedAddFileProgress,
    failure: VerifiedAddFileFailure,
): VerifiedAddFileResult = when (val disposition = recoverVerifiedAddFileTarget(backend, application)) {
    VerifiedAddFileRecoveryDisposition.RolledBack -> VerifiedAddFileResult.RolledBack(
        progress = progress,
        failure = failure,
        action = VerifiedAddFileRecoveryDispositionAction.DELETE_CREATED_TARGET,
    )
    VerifiedAddFileRecoveryDisposition.Cancelled -> VerifiedAddFileResult.RecoveryRequired(
        application = application,
        progress = progress,
        failure = VerifiedAddFileFailure.CANCELLED,
        action = VerifiedAddFileRecoveryDispositionAction.DELETE_CREATED_TARGET,
    )
    is VerifiedAddFileRecoveryDisposition.RecoveryRequired ->
        VerifiedAddFileResult.RecoveryRequired(
            application = application,
            progress = progress,
            failure = failure,
            action = disposition.action,
        )
    is VerifiedAddFileRecoveryDisposition.ReconciliationRequired ->
        VerifiedAddFileResult.ReconciliationRequired(
            application = application,
            progress = progress,
            failure = failure,
            action = disposition.action,
        )
}
