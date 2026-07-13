package io.github.amichne.kast.api.contract.mutation

import kotlinx.serialization.Serializable

@Serializable
data class KastMutationOperationSnapshot(
    val operationId: KastMutationOperationId,
    val idempotencyKey: KastMutationIdempotencyKey,
    val mutationKind: KastSemanticMutationKind,
    val state: KastMutationOperationState,
)
