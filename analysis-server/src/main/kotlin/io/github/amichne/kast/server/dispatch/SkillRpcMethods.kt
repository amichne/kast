package io.github.amichne.kast.server.dispatch

import io.github.amichne.kast.api.contract.mutation.KastMutationExecutionResult
import io.github.amichne.kast.api.contract.mutation.KastSemanticMutation
import io.github.amichne.kast.api.contract.skill.*
import kotlinx.serialization.json.JsonElement

internal suspend fun RpcMethodRouter.dispatchSkillMethod(
    method: String,
    params: JsonElement?,
): JsonElement? = when (method) {
    "symbol/resolve" -> encode(
        KastResolveResponse.serializer(),
        skillRpc.resolve(decodeParams(KastResolveRequest.serializer(), params)),
    )
    "selector/identity" -> encode(
        KastSelectorIdentityResponse.serializer(),
        skillRpc.selectorIdentity(decodeParams(KastSelectorIdentityRequest.serializer(), params)),
    )
    "symbol/discover" -> encode(
        KastDiscoverResponse.serializer(),
        skillRpc.discover(decodeParams(KastDiscoverRequest.serializer(), params)),
    )
    "symbol/references" -> encode(
        KastReferencesResponse.serializer(),
        skillRpc.references(decodeParams(KastReferencesRequest.serializer(), params)),
    )
    "symbol/callers" -> encode(
        KastCallersResponse.serializer(),
        skillRpc.callers(decodeParams(KastCallersRequest.serializer(), params)),
    )
    "symbol/implementations" -> encode(
        KastImplementationsResponse.serializer(),
        skillRpc.implementations(decodeParams(KastImplementationsRequest.serializer(), params)),
    )
    "symbol/hierarchy" -> encode(
        KastHierarchyResponse.serializer(),
        skillRpc.hierarchy(decodeParams(KastHierarchyRequest.serializer(), params)),
    )
    "symbol/scaffold" -> encode(
        KastScaffoldResponse.serializer(),
        skillRpc.scaffold(decodeParams(KastScaffoldRequest.serializer(), params)),
    )
    "symbol/rename" -> encode(
        KastRenameResponse.serializer(),
        skillRpc.rename(decodeParams(KastRenameRequest.serializer(), params)),
    )
    "symbol/write-and-validate" -> encode(
        KastWriteAndValidateResponse.serializer(),
        skillRpc.writeAndValidate(decodeParams(KastWriteAndValidateRequest.serializer(), params)),
    )
    "symbol/add-file" -> encode(
        KastScopeMutationResponse.serializer(),
        skillRpc.addFile(decodeParams(KastAddFileRequest.serializer(), params)),
    )
    "symbol/add-implementation" -> encode(
        KastScopeMutationResponse.serializer(),
        skillRpc.addImplementation(decodeParams(KastAddImplementationRequest.serializer(), params)),
    )
    "symbol/add-statement" -> encode(
        KastScopeMutationResponse.serializer(),
        skillRpc.addStatement(decodeParams(KastAddStatementRequest.serializer(), params)),
    )
    "symbol/replace-declaration" -> encode(
        KastScopeMutationResponse.serializer(),
        skillRpc.replaceDeclaration(decodeParams(KastReplaceDeclarationRequest.serializer(), params)),
    )
    "mutation/submit" -> encode(
        KastMutationExecutionResult.serializer(),
        mutationRpc.submit(decodeParams(KastSemanticMutation.serializer(), params)),
    )
    else -> null
}
