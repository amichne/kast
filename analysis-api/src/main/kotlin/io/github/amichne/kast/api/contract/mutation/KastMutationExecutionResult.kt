package io.github.amichne.kast.api.contract.mutation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface KastMutationExecutionResult {
    val deduplicated: Boolean

    @Serializable
    @SerialName("SUCCEEDED")
    data class Succeeded(
        val result: KastSemanticMutationResult,
        override val deduplicated: Boolean,
    ) : KastMutationExecutionResult

    @Serializable
    @SerialName("FAILED")
    data class Failed(
        val failure: KastMutationFailure,
        override val deduplicated: Boolean,
    ) : KastMutationExecutionResult
}
