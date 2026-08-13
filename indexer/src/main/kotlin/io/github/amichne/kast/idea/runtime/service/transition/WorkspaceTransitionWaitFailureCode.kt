package io.github.amichne.kast.idea

import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.indexer.gradle.settlement.RuntimeProgressAwaitFailure
import io.github.amichne.kast.workspace.spi.WorkspaceMutationAdmissionState
import io.github.amichne.kast.workspace.spi.WorkspaceMutationTransitionFailure
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionFailure
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionWaitFailure

/**
 * Proof transition:
 * `RuntimeProgressAwaitFailure -> WorkspaceTransitionFailure.WaitRejected`.
 *
 * Retains the closed wait-failure identity and progress evidence while dropping implementation
 * exceptions. Raw awaiter evidence may be extracted only at this indexer adapter boundary.
 */
internal fun RuntimeProgressAwaitFailure.toTransitionFailure(): WorkspaceTransitionFailure.WaitRejected =
    WorkspaceTransitionFailure.WaitRejected(
        reason = when (this) {
            is RuntimeProgressAwaitFailure.DeadlineExceeded ->
                WorkspaceTransitionWaitFailure.DeadlineExceeded
            is RuntimeProgressAwaitFailure.ProjectDisposed ->
                WorkspaceTransitionWaitFailure.RuntimeDisposed
            is RuntimeProgressAwaitFailure.Interrupted ->
                WorkspaceTransitionWaitFailure.Interrupted
            is RuntimeProgressAwaitFailure.FutureFailed ->
                WorkspaceTransitionWaitFailure.AwaitFailed
            is RuntimeProgressAwaitFailure.FutureCancelled ->
                WorkspaceTransitionWaitFailure.AwaitCancelled
        },
        stage = evidence.stage.name,
        elapsedMillis = evidence.elapsed.toMillis(),
        noProgressMillis = evidence.noProgress.toMillis(),
    )

/**
 * Proof transition:
 * `WorkspaceMutationAdmissionException -> WorkspaceMutationTransitionFailure`.
 *
 * Converts the legacy admission exception family into finite pending, failed, or revision-movement
 * evidence. The exception protocol remains confined to the semantic-admission adapter.
 */
internal fun IdeaIndexSemanticAdmission.WorkspaceMutationAdmissionException.toTransitionFailure():
    WorkspaceMutationTransitionFailure = when (this) {
    is IdeaIndexSemanticAdmission.WorkspaceMutationAdmissionUnavailableException ->
        WorkspaceMutationTransitionFailure.AdmissionUnavailable(
            when (admissionStatus) {
                is IdeaIndexSemanticAdmission.Status.Pending -> WorkspaceMutationAdmissionState.Pending
                is IdeaIndexSemanticAdmission.Status.Failed -> WorkspaceMutationAdmissionState.Failed
                is IdeaIndexSemanticAdmission.Status.Ready ->
                    error("READY admission cannot be unavailable")
            },
        )

    is IdeaIndexSemanticAdmission.WorkspaceMutationAdmissionInvalidatedException ->
        WorkspaceMutationTransitionFailure.AdmissionMoved(
            expectedRevision = expectedRevision,
            actualRevision = actualRevision,
        )
}

/** Serializes finite transition failure only at the legacy JSON-RPC boundary. */
internal fun WorkspaceTransitionFailure.toConflict(): ConflictException = when (this) {
    WorkspaceTransitionFailure.NotAttached ->
        ConflictException("Workspace transition ingress is not attached to the indexer worker")

    WorkspaceTransitionFailure.Closed ->
        ConflictException("Workspace transition ingress closed before reconciliation completed")

    is WorkspaceTransitionFailure.SemanticAdmissionFailed -> ConflictException(
        message = "Workspace semantic admission failed after reconciliation",
        details = mapOf("admissionState" to "FAILED", "detail" to detail),
    )

    is WorkspaceTransitionFailure.Blocked -> ConflictException(
        message = "Workspace reconciliation is blocked: ${blocker.detail}",
        details = mapOf("phase" to blocker.phase.name),
    )

    is WorkspaceTransitionFailure.InvalidCompletion -> ConflictException(
        message = "Workspace transition published an invalid completion state",
        details = mapOf("lifecycle" to lifecycle.name),
    )

    is WorkspaceTransitionFailure.WaitRejected -> ConflictException(
        message = "Workspace reconciliation did not publish READY within the indexing wait policy",
        details = mapOf(
            "waitFailure" to reason.name,
            "stage" to stage,
            "elapsedMillis" to elapsedMillis.toString(),
            "noProgressMillis" to noProgressMillis.toString(),
        ),
    )
}

/** Serializes finite mutation-transition failure only at the legacy JSON-RPC boundary. */
internal fun WorkspaceMutationTransitionFailure.toConflict(): ConflictException = when (this) {
    is WorkspaceMutationTransitionFailure.AdmissionUnavailable -> ConflictException(
        message = "Workspace changed before the mutation could begin",
        details = mapOf("admissionState" to state.name.uppercase()),
    )

    is WorkspaceMutationTransitionFailure.AdmissionMoved -> ConflictException(
        message = "Workspace changed before the mutation could begin",
        details = mapOf(
            "expectedAdmissionRevision" to expectedRevision.toString(),
            "actualAdmissionRevision" to actualRevision.toString(),
        ),
    )

    is WorkspaceMutationTransitionFailure.ReconciliationRejected -> failure.toConflict()
}
