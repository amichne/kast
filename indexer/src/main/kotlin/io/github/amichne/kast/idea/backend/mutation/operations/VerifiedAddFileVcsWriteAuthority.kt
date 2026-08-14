package io.github.amichne.kast.idea.backend.mutation.operations

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.amichne.kast.api.contract.CreateFileParentPolicy
import io.github.amichne.kast.api.contract.FileOperation
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.PartialApplyException
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.server.change.VerifiedAddFileAdmission
import io.github.amichne.kast.server.change.VerifiedAddFileFailure
import io.github.amichne.kast.server.change.VerifiedAddFileProgress
import io.github.amichne.kast.server.change.VerifiedAddFileReconciliationAction
import io.github.amichne.kast.server.change.VerifiedAddFileRecoveryId
import java.nio.file.Path
import java.util.concurrent.CancellationException

internal sealed interface VerifiedAddFileSourceApplication {
    data class Applied(
        val application: AppliedVerifiedAddFile,
    ) : VerifiedAddFileSourceApplication

    data class AppliedFailure(
        val application: AppliedVerifiedAddFile,
        val failure: VerifiedAddFileFailure,
    ) : VerifiedAddFileSourceApplication

    data class CommitUnproven(
        val recoveryId: VerifiedAddFileRecoveryId,
        val failure: VerifiedAddFileFailure,
    ) : VerifiedAddFileSourceApplication
}

internal class VerifiedAddFileVcsWriteAuthorized private constructor(
    private val recovery: VerifiedAddFileRecoveryPrepared,
) {
    internal suspend fun applyPlannedTarget(
        backend: KastIndexerBackend,
    ): VerifiedAddFileSourceApplication =
        applyPlannedTarget { query -> backend.applyEdits(query.parsed()) }

    /**
     * Effect transition: `VerifiedAddFileVcsWriteAuthorized -> VerifiedAddFileSourceApplication`.
     *
     * Establishes delete-capable [AppliedVerifiedAddFile] authority only from a successful typed
     * backend result or structured partial-apply evidence that this operation committed the exact
     * planned target. Conflict, cancellation, and unproven failure retain only a recovery identity
     * and require non-destructive reconciliation. The supplied effect is the sole raw backend
     * application boundary.
     */
    internal suspend fun applyPlannedTarget(
        apply: suspend (ApplyEditsQuery) -> ApplyEditsResult,
    ): VerifiedAddFileSourceApplication {
        val intent = recovery.plan.planned.intent
        val query = ApplyEditsQuery(
            edits = emptyList(),
            fileHashes = emptyList(),
            fileOperations = listOf(
                FileOperation.CreateFile(
                    filePath = intent.targetPath.value,
                    content = intent.content.value,
                    parentPolicy = CreateFileParentPolicy.REQUIRE_EXISTING_PARENTS,
                ),
            ),
        )
        return try {
            when (val admission = AppliedVerifiedAddFile.admit(recovery, apply(query))) {
                is VerifiedAddFileProofAdmission.Admitted ->
                    VerifiedAddFileSourceApplication.Applied(admission.value)
                is VerifiedAddFileProofAdmission.Rejected ->
                    VerifiedAddFileSourceApplication.CommitUnproven(
                        recovery.recoveryId,
                        admission.failure,
                    )
            }
        } catch (_: ProcessCanceledException) {
            VerifiedAddFileSourceApplication.CommitUnproven(
                recovery.recoveryId,
                VerifiedAddFileFailure.CANCELLED,
            )
        } catch (_: CancellationException) {
            VerifiedAddFileSourceApplication.CommitUnproven(
                recovery.recoveryId,
                VerifiedAddFileFailure.CANCELLED,
            )
        } catch (failure: PartialApplyException) {
            when (val admission = AppliedVerifiedAddFile.admit(recovery, failure)) {
                is VerifiedAddFileProofAdmission.Admitted ->
                    VerifiedAddFileSourceApplication.AppliedFailure(
                        admission.value,
                        VerifiedAddFileFailure.SOURCE_APPLICATION_FAILED,
                    )
                is VerifiedAddFileProofAdmission.Rejected ->
                    VerifiedAddFileSourceApplication.CommitUnproven(
                        recovery.recoveryId,
                        admission.failure,
                    )
            }
        } catch (_: ConflictException) {
            VerifiedAddFileSourceApplication.CommitUnproven(
                recovery.recoveryId,
                VerifiedAddFileFailure.SOURCE_APPLICATION_FAILED,
            )
        } catch (_: Exception) {
            VerifiedAddFileSourceApplication.CommitUnproven(
                recovery.recoveryId,
                VerifiedAddFileFailure.SOURCE_APPLICATION_FAILED,
            )
        }
    }

    companion object {
        /**
         * Proof transition:
         * `VerifiedAddFileRecoveryPrepared`
         * to `VerifiedAddFileAdmission<VerifiedAddFileVcsWriteAuthorized>`.
         *
         * Establishes that the exact planned target parent is VFS-writable and therefore cannot
         * require an interactive VCS write prompt. The closed expected failure is
         * [VerifiedAddFileFailure.VCS_WRITE_PROMPT_REJECTED]. Raw paths are extracted only while
         * resolving the parent VFS node at this IntelliJ write-admission boundary.
         */
        internal fun admit(
            recovery: VerifiedAddFileRecoveryPrepared,
        ): VerifiedAddFileAdmission<VerifiedAddFileVcsWriteAuthorized> {
            val target = Path.of(recovery.plan.planned.intent.targetPath.value)
            val parent = target.parent
                ?: return VerifiedAddFileAdmission.Rejected(
                    VerifiedAddFileFailure.VCS_WRITE_PROMPT_REJECTED,
                )
            val virtualParent = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(parent)
                ?: return VerifiedAddFileAdmission.Rejected(
                    VerifiedAddFileFailure.VCS_WRITE_PROMPT_REJECTED,
                )
            return if (virtualParent.isWritable) {
                VerifiedAddFileAdmission.Admitted(VerifiedAddFileVcsWriteAuthorized(recovery))
            } else {
                VerifiedAddFileAdmission.Rejected(VerifiedAddFileFailure.VCS_WRITE_PROMPT_REJECTED)
            }
        }
    }
}

/**
 * Proof-preserving projection:
 * `VerifiedAddFileSourceApplication.CommitUnproven -> VerifiedAddFileResult`.
 *
 * Retains only a recovery identity and emits non-destructive reconciliation; it cannot acquire the
 * [AppliedVerifiedAddFile] capability required by exact-CAS deletion.
 */
internal fun VerifiedAddFileSourceApplication.CommitUnproven.toResult(): VerifiedAddFileResult =
    VerifiedAddFileResult.NonDestructiveReconciliationRequired(
        recoveryId = recoveryId,
        progress = VerifiedAddFileProgress.SOURCE_APPLICATION,
        failure = failure,
        action = VerifiedAddFileReconciliationAction.INSPECT_TARGET,
    )
