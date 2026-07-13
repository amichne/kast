package io.github.amichne.kast.api.contract.mutation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface KastMutationOperationSelector {
    @Serializable
    @SerialName("BY_OPERATION_ID")
    data class ByOperationId(
        val operationId: KastMutationOperationId,
    ) : KastMutationOperationSelector

    @Serializable
    @SerialName("BY_IDEMPOTENCY_KEY")
    data class ByIdempotencyKey(
        val idempotencyKey: KastMutationIdempotencyKey,
    ) : KastMutationOperationSelector
}
