package io.github.amichne.kast.server.mutation

import io.github.amichne.kast.api.contract.mutation.KastMutationExecutionResult
import io.github.amichne.kast.api.contract.mutation.KastMutationFailure
import io.github.amichne.kast.api.contract.mutation.KastSemanticMutation
import io.github.amichne.kast.api.contract.mutation.KastSemanticMutationResult
import io.github.amichne.kast.api.contract.skill.KastRenameFailureResponse
import io.github.amichne.kast.api.contract.skill.KastRenameSuccessResponse
import io.github.amichne.kast.api.contract.skill.KastScopeMutationFailureResponse
import io.github.amichne.kast.api.contract.skill.KastScopeMutationSuccessResponse
import io.github.amichne.kast.api.contract.skill.KastSelectorHandleRejectedResponse
import io.github.amichne.kast.api.protocol.AnalysisException
import io.github.amichne.kast.api.protocol.ApiErrorResponse
import io.github.amichne.kast.server.SkillRpcOrchestrator

internal class MutationExecutionService(
    private val skillRpc: SkillRpcOrchestrator,
) {
    suspend fun submit(mutation: KastSemanticMutation): KastMutationExecutionResult {
        val outcome = try {
            when (mutation) {
                is KastSemanticMutation.Rename -> skillRpc.rename(mutation.request).toOutcome()
                is KastSemanticMutation.AddFile -> skillRpc.addFile(mutation.request).toOutcome()
                is KastSemanticMutation.AddDeclaration -> skillRpc.addDeclaration(mutation.request).toOutcome()
                is KastSemanticMutation.AddImplementation -> skillRpc.addImplementation(mutation.request).toOutcome()
                is KastSemanticMutation.AddStatement -> skillRpc.addStatement(mutation.request).toOutcome()
                is KastSemanticMutation.ReplaceDeclaration -> skillRpc.replaceDeclaration(mutation.request).toOutcome()
            }
        } catch (exception: Throwable) {
            ExecutionOutcome.Failed(
                KastMutationFailure.Thrown(exception.toApiError(mutation.idempotencyKey.value)),
            )
        }
        return outcome.toResult()
    }
}

private sealed interface ExecutionOutcome {
    data class Succeeded(val result: KastSemanticMutationResult) : ExecutionOutcome
    data class Failed(val failure: KastMutationFailure) : ExecutionOutcome
}

private fun ExecutionOutcome.toResult(): KastMutationExecutionResult = when (this) {
    is ExecutionOutcome.Succeeded -> KastMutationExecutionResult.Succeeded(result, deduplicated = false)
    is ExecutionOutcome.Failed -> KastMutationExecutionResult.Failed(failure, deduplicated = false)
}

private fun io.github.amichne.kast.api.contract.skill.KastRenameResponse.toOutcome(): ExecutionOutcome = when (this) {
    is KastRenameSuccessResponse -> if (ok) {
        ExecutionOutcome.Succeeded(KastSemanticMutationResult.Rename(this))
    } else {
        ExecutionOutcome.Failed(KastMutationFailure.AppliedInvalidRename(this))
    }
    is KastRenameFailureResponse -> ExecutionOutcome.Failed(KastMutationFailure.Rename(this))
    is KastSelectorHandleRejectedResponse -> ExecutionOutcome.Failed(KastMutationFailure.SelectorHandleRejected(this))
}

private fun io.github.amichne.kast.api.contract.skill.KastScopeMutationResponse.toOutcome(): ExecutionOutcome = when (this) {
    is KastScopeMutationSuccessResponse -> if (ok) {
        ExecutionOutcome.Succeeded(KastSemanticMutationResult.Scope(this))
    } else {
        ExecutionOutcome.Failed(KastMutationFailure.AppliedInvalidScope(this))
    }
    is KastScopeMutationFailureResponse -> ExecutionOutcome.Failed(KastMutationFailure.Scope(this))
    is KastSelectorHandleRejectedResponse -> ExecutionOutcome.Failed(KastMutationFailure.SelectorHandleRejected(this))
}

private fun Throwable.toApiError(requestId: String): ApiErrorResponse = when (this) {
    is AnalysisException -> ApiErrorResponse(
        requestId = requestId,
        code = errorCode,
        message = message,
        retryable = retryable,
        details = details,
    )
    else -> ApiErrorResponse(
        requestId = requestId,
        code = "MUTATION_EXECUTION_FAILED",
        message = message ?: this::class.java.simpleName,
        retryable = false,
    )
}
