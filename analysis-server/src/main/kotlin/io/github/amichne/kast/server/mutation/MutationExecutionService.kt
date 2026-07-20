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
import io.github.amichne.kast.server.mutation.coordination.MutationFinishBarrierRequest
import io.github.amichne.kast.server.mutation.coordination.MutationFinishBarrierResult
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal class MutationExecutionService(
    private val skillRpc: SkillRpcOrchestrator,
    private val json: Json,
) : Closeable {
    private val workerJob: CompletableJob = SupervisorJob()
    private val coordinator = MutationCoordinator(CoroutineScope(workerJob + Dispatchers.Default))

    suspend fun submit(mutation: KastSemanticMutation): KastMutationExecutionResult = coordinator.execute(
        taskId = mutation.workspaceTaskId,
        idempotencyKey = mutation.idempotencyKey,
        fingerprint = mutation.fingerprint(),
    ) {
        try {
            when (mutation) {
                is KastSemanticMutation.Rename -> skillRpc.rename(mutation.request).toOutcome()
                is KastSemanticMutation.AddFile -> skillRpc.addFile(mutation.request).toOutcome()
                is KastSemanticMutation.AddDeclaration -> skillRpc.addDeclaration(mutation.request).toOutcome()
                is KastSemanticMutation.AddImplementation -> skillRpc.addImplementation(mutation.request).toOutcome()
                is KastSemanticMutation.AddStatement -> skillRpc.addStatement(mutation.request).toOutcome()
                is KastSemanticMutation.ReplaceDeclaration -> skillRpc.replaceDeclaration(mutation.request).toOutcome()
            }
        } catch (exception: Throwable) {
            MutationCoordinator.ExecutionOutcome.Failed(
                KastMutationFailure.Thrown(exception.toApiError(mutation.idempotencyKey.value)),
            )
        }
    }

    suspend fun acquireFinishBarrier(request: MutationFinishBarrierRequest): MutationFinishBarrierResult =
        coordinator.acquireFinishBarrier(request)

    fun reopenAfterFinish(request: MutationFinishBarrierRequest): MutationFinishBarrierResult =
        coordinator.reopenAfterFinish(request)

    fun repairAfterInterruptedFinish(request: MutationFinishBarrierRequest): MutationFinishBarrierResult =
        coordinator.repairAfterInterruptedFinish(request)

    fun completeAfterFinish(request: MutationFinishBarrierRequest): MutationFinishBarrierResult =
        coordinator.completeAfterFinish(request)

    override fun close() {
        coordinator.close()
        workerJob.cancel()
    }

    private fun KastSemanticMutation.fingerprint(): MutationFingerprint {
        val request = when (this) {
            is KastSemanticMutation.Rename -> canonicalRequest(mutationRequestSerializer(), request)
            is KastSemanticMutation.AddFile -> canonicalRequest(mutationRequestSerializer(), request)
            is KastSemanticMutation.AddDeclaration -> canonicalRequest(mutationRequestSerializer(), request)
            is KastSemanticMutation.AddImplementation -> canonicalRequest(mutationRequestSerializer(), request)
            is KastSemanticMutation.AddStatement -> canonicalRequest(mutationRequestSerializer(), request)
            is KastSemanticMutation.ReplaceDeclaration -> canonicalRequest(mutationRequestSerializer(), request)
        }
        return MutationFingerprint(
            MessageDigest.getInstance("SHA-256")
                .digest("$symbolMethod\n$request".toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) },
        )
    }

    private fun <T> canonicalRequest(serializer: KSerializer<T>, request: T): String =
        json.encodeToJsonElement(serializer, request).canonical().toString()
}

private fun JsonElement.canonical(): JsonElement = when (this) {
    is JsonArray -> JsonArray(map(JsonElement::canonical))
    is JsonObject -> JsonObject(entries.sortedBy { it.key }.associate { (key, value) -> key to value.canonical() })
    else -> this
}

private fun io.github.amichne.kast.api.contract.skill.KastRenameResponse.toOutcome(): MutationCoordinator.ExecutionOutcome =
    when (this) {
        is KastRenameSuccessResponse -> if (ok) {
            MutationCoordinator.ExecutionOutcome.Succeeded(KastSemanticMutationResult.Rename(this))
        } else {
            MutationCoordinator.ExecutionOutcome.Failed(KastMutationFailure.AppliedInvalidRename(this))
        }
        is KastRenameFailureResponse -> MutationCoordinator.ExecutionOutcome.Failed(KastMutationFailure.Rename(this))
        is KastSelectorHandleRejectedResponse ->
            MutationCoordinator.ExecutionOutcome.Failed(KastMutationFailure.SelectorHandleRejected(this))
    }

private fun io.github.amichne.kast.api.contract.skill.KastScopeMutationResponse.toOutcome(): MutationCoordinator.ExecutionOutcome =
    when (this) {
        is KastScopeMutationSuccessResponse -> if (ok) {
            MutationCoordinator.ExecutionOutcome.Succeeded(KastSemanticMutationResult.Scope(this))
        } else {
            MutationCoordinator.ExecutionOutcome.Failed(KastMutationFailure.AppliedInvalidScope(this))
        }
        is KastScopeMutationFailureResponse -> MutationCoordinator.ExecutionOutcome.Failed(KastMutationFailure.Scope(this))
        is KastSelectorHandleRejectedResponse ->
            MutationCoordinator.ExecutionOutcome.Failed(KastMutationFailure.SelectorHandleRejected(this))
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

@Suppress("UNCHECKED_CAST")
private inline fun <reified T> mutationRequestSerializer(): KSerializer<T> = kotlinx.serialization.serializer<T>()
