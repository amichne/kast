package io.github.amichne.kast.api.contract.mutation

import io.github.amichne.kast.api.contract.skill.KastRenameFailureResponse
import io.github.amichne.kast.api.contract.skill.KastRenameSuccessResponse
import io.github.amichne.kast.api.contract.skill.KastScopeMutationFailureResponse
import io.github.amichne.kast.api.contract.skill.KastScopeMutationSuccessResponse
import io.github.amichne.kast.api.protocol.ApiErrorResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface KastMutationFailure {
    @Serializable
    @SerialName("RENAME_FAILURE")
    data class Rename(
        val response: KastRenameFailureResponse,
    ) : KastMutationFailure

    @Serializable
    @SerialName("SCOPE_MUTATION_FAILURE")
    data class Scope(
        val response: KastScopeMutationFailureResponse,
    ) : KastMutationFailure

    @Serializable
    @SerialName("APPLIED_INVALID_RENAME")
    data class AppliedInvalidRename(
        val response: KastRenameSuccessResponse,
    ) : KastMutationFailure

    @Serializable
    @SerialName("APPLIED_INVALID_SCOPE")
    data class AppliedInvalidScope(
        val response: KastScopeMutationSuccessResponse,
    ) : KastMutationFailure

    @Serializable
    @SerialName("THROWN_FAILURE")
    data class Thrown(
        val error: ApiErrorResponse,
    ) : KastMutationFailure
}
