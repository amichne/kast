package io.github.amichne.kast.server.mutation

import io.github.amichne.kast.api.contract.mutation.KastMutationFailure
import io.github.amichne.kast.api.contract.mutation.KastMutationOperationSelector
import io.github.amichne.kast.api.contract.mutation.KastMutationOperationSnapshot
import io.github.amichne.kast.api.contract.mutation.KastMutationSubmissionReceipt
import io.github.amichne.kast.api.contract.mutation.KastSemanticMutation
import io.github.amichne.kast.api.contract.mutation.KastSemanticMutationResult
import io.github.amichne.kast.api.contract.skill.KastRenameFailureResponse
import io.github.amichne.kast.api.contract.skill.KastRenameSuccessResponse
import io.github.amichne.kast.api.contract.skill.KastScopeMutationFailureResponse
import io.github.amichne.kast.api.contract.skill.KastScopeMutationSuccessResponse
import io.github.amichne.kast.server.SkillRpcOrchestrator
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal class MutationOperationService(
    private val skillRpc: SkillRpcOrchestrator,
    private val json: Json,
    private val registry: MutationOperationRegistry = MutationOperationRegistry(),
) {
    fun submit(mutation: KastSemanticMutation): KastMutationSubmissionReceipt = registry.submit(
        mutation = mutation,
        fingerprint = mutation.fingerprint(),
    ) { reporter ->
        when (mutation) {
            is KastSemanticMutation.Rename -> skillRpc.rename(mutation.request, reporter).toOutcome()
            is KastSemanticMutation.AddFile -> skillRpc.addFile(mutation.request, reporter).toOutcome()
            is KastSemanticMutation.AddDeclaration -> skillRpc.addDeclaration(mutation.request, reporter).toOutcome()
            is KastSemanticMutation.AddImplementation -> skillRpc.addImplementation(mutation.request, reporter).toOutcome()
            is KastSemanticMutation.AddStatement -> skillRpc.addStatement(mutation.request, reporter).toOutcome()
            is KastSemanticMutation.ReplaceDeclaration -> skillRpc.replaceDeclaration(mutation.request, reporter).toOutcome()
        }
    }

    fun status(selector: KastMutationOperationSelector): KastMutationOperationSnapshot = registry.status(selector)

    fun cancel(selector: KastMutationOperationSelector): KastMutationOperationSnapshot = registry.cancel(selector)

    private fun KastSemanticMutation.fingerprint(): MutationFingerprint {
        val request = when (this) {
            is KastSemanticMutation.Rename -> canonicalRequest(mutationRequestSerializer(), request)
            is KastSemanticMutation.AddFile -> canonicalRequest(mutationRequestSerializer(), request)
            is KastSemanticMutation.AddDeclaration -> canonicalRequest(mutationRequestSerializer(), request)
            is KastSemanticMutation.AddImplementation -> canonicalRequest(mutationRequestSerializer(), request)
            is KastSemanticMutation.AddStatement -> canonicalRequest(mutationRequestSerializer(), request)
            is KastSemanticMutation.ReplaceDeclaration -> canonicalRequest(mutationRequestSerializer(), request)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$symbolMethod\n$request".toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return MutationFingerprint(digest)
    }

    private fun <T> canonicalRequest(serializer: KSerializer<T>, request: T): String =
        json.encodeToJsonElement(serializer, request).canonical().toString()
}

private fun JsonElement.canonical(): JsonElement = when (this) {
    is JsonArray -> JsonArray(map(JsonElement::canonical))
    is JsonObject -> JsonObject(entries.sortedBy(Map.Entry<String, JsonElement>::key).associate { (key, value) ->
        key to value.canonical()
    })
    else -> this
}

private fun KastRenameSuccessResponse.toOutcome(): MutationOperationRegistry.ExecutionOutcome =
    MutationOperationRegistry.ExecutionOutcome.Succeeded(KastSemanticMutationResult.Rename(this))

private fun KastRenameFailureResponse.toOutcome(): MutationOperationRegistry.ExecutionOutcome =
    MutationOperationRegistry.ExecutionOutcome.Failed(KastMutationFailure.Rename(this))

private fun io.github.amichne.kast.api.contract.skill.KastRenameResponse.toOutcome(): MutationOperationRegistry.ExecutionOutcome =
    when (this) {
        is KastRenameSuccessResponse -> toOutcome()
        is KastRenameFailureResponse -> toOutcome()
    }

private fun io.github.amichne.kast.api.contract.skill.KastScopeMutationResponse.toOutcome(): MutationOperationRegistry.ExecutionOutcome =
    when (this) {
        is KastScopeMutationSuccessResponse ->
            MutationOperationRegistry.ExecutionOutcome.Succeeded(KastSemanticMutationResult.Scope(this))

        is KastScopeMutationFailureResponse ->
            MutationOperationRegistry.ExecutionOutcome.Failed(KastMutationFailure.Scope(this))
    }

@Suppress("UNCHECKED_CAST")
private inline fun <reified T> mutationRequestSerializer(): KSerializer<T> =
    kotlinx.serialization.serializer<T>()
