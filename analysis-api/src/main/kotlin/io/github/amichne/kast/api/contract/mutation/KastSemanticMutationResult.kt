package io.github.amichne.kast.api.contract.mutation

import io.github.amichne.kast.api.contract.skill.KastRenameSuccessResponse
import io.github.amichne.kast.api.contract.skill.KastScopeMutationSuccessResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface KastSemanticMutationResult {
    @Serializable
    @SerialName("RENAME_RESULT")
    data class Rename(
        val response: KastRenameSuccessResponse,
    ) : KastSemanticMutationResult

    @Serializable
    @SerialName("SCOPE_MUTATION_RESULT")
    data class Scope(
        val response: KastScopeMutationSuccessResponse,
    ) : KastSemanticMutationResult
}
