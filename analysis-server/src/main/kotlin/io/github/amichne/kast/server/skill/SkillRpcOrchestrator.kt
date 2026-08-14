package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.AnalysisBackend
import io.github.amichne.kast.api.contract.skill.*
import io.github.amichne.kast.server.skill.*
import kotlinx.serialization.json.Json

internal class SkillRpcOrchestrator(
    private val backend: AnalysisBackend,
    private val config: AnalysisServerConfig,
    private val publicSymbolReads: PublicSymbolReadBinding,
    private val json: Json,
) {
    private val context = SkillRpcContext(backend, config, publicSymbolReads)

    suspend fun resolve(request: KastResolveRequest): KastResolveResponse = context.resolve(request)

    suspend fun selectorIdentity(request: KastSelectorIdentityRequest): KastSelectorIdentityResponse =
        context.selectorIdentity(request)

    suspend fun discover(request: KastDiscoverRequest): KastDiscoverResponse = context.discover(request)

    suspend fun references(request: KastReferencesRequest): KastReferencesResponse = context.references(request)

    suspend fun callers(request: KastCallersRequest): KastCallersResponse = context.callers(request)

    suspend fun implementations(request: KastImplementationsRequest): KastImplementationsResponse =
        context.implementations(request)

    suspend fun hierarchy(request: KastHierarchyRequest): KastHierarchyResponse = context.hierarchy(request)

    suspend fun scaffold(request: KastScaffoldRequest): KastScaffoldResponse = context.scaffold(request)

    suspend fun rename(request: KastRenameRequest): KastRenameResponse = context.rename(request)

    suspend fun writeAndValidate(request: KastWriteAndValidateRequest): KastWriteAndValidateResponse =
        context.writeAndValidate(request)

    suspend fun addFile(request: KastAddFileRequest): KastScopeMutationResponse = context.addFile(request)

    suspend fun addImplementation(request: KastAddImplementationRequest): KastScopeMutationResponse =
        context.addImplementation(request)

    suspend fun addStatement(request: KastAddStatementRequest): KastScopeMutationResponse =
        context.addStatement(request)

    suspend fun replaceDeclaration(request: KastReplaceDeclarationRequest): KastScopeMutationResponse =
        context.replaceDeclaration(request)
}
