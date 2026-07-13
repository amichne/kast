package io.github.amichne.kast.api.contract.mutation

import kotlinx.serialization.Serializable

@Serializable
data class KastMutationOperationSnapshot(
    val operationId: KastMutationOperationId,
    val idempotencyKey: KastMutationIdempotencyKey,
    val mutationKind: KastSemanticMutationKind,
    val state: KastMutationOperationState,
) {
    val safeForFilesystemFallback: Boolean
        get() = when (state) {
            is KastMutationOperationState.Failed,
            is KastMutationOperationState.Cancelled,
            -> state.trace.editApplicationState == KastMutationEditApplicationState.NOT_STARTED

            is KastMutationOperationState.Queued,
            is KastMutationOperationState.Applying,
            is KastMutationOperationState.Validating,
            is KastMutationOperationState.Completed,
            -> false
        }
}
