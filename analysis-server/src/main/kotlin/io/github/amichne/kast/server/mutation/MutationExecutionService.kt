package io.github.amichne.kast.server.mutation

import io.github.amichne.kast.api.contract.mutation.KastMutationExecutionResult
import io.github.amichne.kast.api.contract.mutation.KastMutationFailure
import io.github.amichne.kast.api.contract.mutation.KastMutationIdempotencyKey
import io.github.amichne.kast.api.contract.mutation.KastSemanticMutation
import io.github.amichne.kast.api.contract.mutation.KastSemanticMutationResult
import io.github.amichne.kast.api.contract.skill.KastRenameFailureResponse
import io.github.amichne.kast.api.contract.skill.KastRenameSuccessResponse
import io.github.amichne.kast.api.contract.skill.KastScopeMutationFailureResponse
import io.github.amichne.kast.api.contract.skill.KastScopeMutationSuccessResponse
import io.github.amichne.kast.api.contract.skill.KastSelectorHandleRejectedResponse
import io.github.amichne.kast.api.protocol.AnalysisException
import io.github.amichne.kast.api.protocol.ApiErrorResponse
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.server.SkillRpcOrchestrator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal class MutationExecutionService(
    private val skillRpc: SkillRpcOrchestrator,
) {
    private val lock = Any()
    private val executions = mutableMapOf<KastMutationIdempotencyKey, ExecutionEntry>()

    suspend fun submit(mutation: KastSemanticMutation): KastMutationExecutionResult {
        val fingerprint = mutation.fingerprint()
        val submission = synchronized(lock) {
            executions[mutation.idempotencyKey]?.let { existing ->
                if (existing.fingerprint != fingerprint) {
                    throw ConflictException(
                        message = "Mutation idempotency key is already bound to another request",
                        details = mapOf("idempotencyKey" to mutation.idempotencyKey.value),
                    )
                }
                return@synchronized Submission(existing, deduplicated = true)
            }
            Submission(ExecutionEntry(fingerprint).also { executions[mutation.idempotencyKey] = it }, deduplicated = false)
        }
        if (!submission.deduplicated) submission.entry.result.complete(execute(mutation))
        return submission.entry.result.await().toResult(submission.deduplicated)
    }

    private suspend fun execute(mutation: KastSemanticMutation): ExecutionOutcome {
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
        return outcome
    }

    private fun KastSemanticMutation.fingerprint(): MutationFingerprint {
        val request = Json.encodeToJsonElement(KastSemanticMutation.serializer(), this).canonical().toString()
        return MutationFingerprint(
            MessageDigest.getInstance("SHA-256")
                .digest(request.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) },
        )
    }
}

private fun JsonElement.canonical(): JsonElement = when (this) {
    is JsonArray -> JsonArray(map(JsonElement::canonical))
    is JsonObject -> JsonObject(entries.sortedBy { it.key }.associate { (key, value) -> key to value.canonical() })
    else -> this
}

private sealed interface ExecutionOutcome {
    data class Succeeded(val result: KastSemanticMutationResult) : ExecutionOutcome
    data class Failed(val failure: KastMutationFailure) : ExecutionOutcome
}

private fun ExecutionOutcome.toResult(deduplicated: Boolean): KastMutationExecutionResult = when (this) {
    is ExecutionOutcome.Succeeded -> KastMutationExecutionResult.Succeeded(result, deduplicated)
    is ExecutionOutcome.Failed -> KastMutationExecutionResult.Failed(failure, deduplicated)
}

private class ExecutionEntry(
    val fingerprint: MutationFingerprint,
    val result: CompletableDeferred<ExecutionOutcome> = CompletableDeferred(),
)

private data class Submission(val entry: ExecutionEntry, val deduplicated: Boolean)

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
