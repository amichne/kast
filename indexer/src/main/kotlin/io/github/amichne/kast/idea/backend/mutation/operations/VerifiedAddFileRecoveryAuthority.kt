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
import io.github.amichne.kast.server.change.RevalidatedVerifiedAddFilePlan
import io.github.amichne.kast.server.change.VerifiedAddFileFailure
import io.github.amichne.kast.server.change.VerifiedAddFileReconciliationAction
import io.github.amichne.kast.server.change.VerifiedAddFileRecoveryDisposition
import io.github.amichne.kast.server.change.VerifiedAddFileRecoveryDispositionAction
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
 * Accepts either a structured committed exact delete or absence observed under artifact-free
 * [AppliedVerifiedAddFile] authority. RolledBack is emitted only after a focused refresh returns
 * the exact target as removed with a complete semantic outcome. Any incomplete publication retains
 * delete recovery authority. Raw target paths are extracted only at this refresh boundary.
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
