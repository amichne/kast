package io.github.amichne.kast.cli

import io.github.amichne.kast.api.contract.ApplyEditsQuery
import io.github.amichne.kast.api.contract.ApplyEditsResult
import io.github.amichne.kast.api.contract.BackendCapabilities
import io.github.amichne.kast.api.contract.CallHierarchyQuery
import io.github.amichne.kast.api.contract.CallHierarchyResult
import io.github.amichne.kast.api.protocol.CapabilityNotSupportedException
import io.github.amichne.kast.api.contract.CodeActionsQuery
import io.github.amichne.kast.api.contract.CodeActionsResult
import io.github.amichne.kast.api.contract.CompletionsQuery
import io.github.amichne.kast.api.contract.CompletionsResult
import io.github.amichne.kast.api.contract.DiagnosticsQuery
import io.github.amichne.kast.api.contract.DiagnosticsResult
import io.github.amichne.kast.api.contract.FileOutlineQuery
import io.github.amichne.kast.api.contract.FileOutlineResult
import io.github.amichne.kast.api.contract.ImportOptimizeQuery
import io.github.amichne.kast.api.contract.ImportOptimizeResult
import io.github.amichne.kast.api.contract.ImplementationsQuery
import io.github.amichne.kast.api.contract.ImplementationsResult
import io.github.amichne.kast.api.contract.MutationCapability
import io.github.amichne.kast.api.contract.ReadCapability
import io.github.amichne.kast.api.contract.RefreshQuery
import io.github.amichne.kast.api.contract.RefreshResult
import io.github.amichne.kast.api.contract.ReferencesQuery
import io.github.amichne.kast.api.contract.ReferencesResult
import io.github.amichne.kast.api.contract.RenameQuery
import io.github.amichne.kast.api.contract.RenameResult
import io.github.amichne.kast.api.contract.SemanticInsertionQuery
import io.github.amichne.kast.api.contract.SemanticInsertionResult
import io.github.amichne.kast.api.contract.SymbolQuery
import io.github.amichne.kast.api.contract.SymbolResult
import io.github.amichne.kast.api.contract.TypeHierarchyQuery
import io.github.amichne.kast.api.contract.TypeHierarchyResult
import io.github.amichne.kast.api.contract.WorkspaceFilesQuery
import io.github.amichne.kast.api.contract.WorkspaceFilesResult
import io.github.amichne.kast.api.contract.WorkspaceSymbolQuery
import io.github.amichne.kast.api.contract.WorkspaceSymbolResult
import io.github.amichne.kast.cli.runtime.DaemonStopResult
import io.github.amichne.kast.cli.runtime.RuntimeCandidateStatus
import io.github.amichne.kast.cli.runtime.RuntimeLifecycleRequest
import io.github.amichne.kast.cli.runtime.RuntimeSelection
import io.github.amichne.kast.cli.runtime.WorkspaceEnsureResult
import io.github.amichne.kast.cli.runtime.WorkspaceRuntimeManager
import io.github.amichne.kast.cli.runtime.WorkspaceStatusResult
import kotlinx.serialization.json.Json

internal class CliService(
    json: Json,
    private val installService: InstallService = InstallService(),
    private val installSkillService: InstallSkillService = InstallSkillService(),
    private val smokeCommandSupport: SmokeCommandSupport = SmokeCommandSupport(),
) {
    private val rpcClient = KastRpcClient(json)
    private val runtimeManager = WorkspaceRuntimeManager(rpcClient)

    suspend fun workspaceStatus(request: RuntimeLifecycleRequest): WorkspaceStatusResult =
        runtimeManager.workspaceStatus(request)

    suspend fun workspaceEnsure(request: RuntimeLifecycleRequest): WorkspaceEnsureResult =
        runtimeManager.workspaceEnsure(request)

    suspend fun workspaceRefresh(
        request: RuntimeLifecycleRequest,
        query: RefreshQuery,
    ): RuntimeAttachedResult<RefreshResult> {
        val runtime = runtimeManager.ensureRuntime(request)
        requireMutationCapability(runtime.selected, MutationCapability.REFRESH_WORKSPACE)
        return attachedResult(
            payload = rpcClient.post(runtime.selected.descriptor, "workspace/refresh", query),
            runtime = runtime,
        )
    }

    suspend fun workspaceStop(request: RuntimeLifecycleRequest): DaemonStopResult =
        runtimeManager.workspaceStop(request)

    suspend fun capabilities(runtime: RuntimeSelection): RuntimeAttachedResult<BackendCapabilities> {
        val ensuredRuntime = runtimeManager.ensureRuntime(lifecycleRequest(runtime))
        val capabilities = checkNotNull(ensuredRuntime.selected.capabilities) {
            "Runtime capabilities were not loaded after ensure for ${ensuredRuntime.selected.descriptor.backendName}"
        }
        return attachedResult(
            payload = capabilities,
            runtime = ensuredRuntime,
        )
    }

    suspend fun resolveSymbol(
        runtime: RuntimeSelection,
        query: SymbolQuery,
    ): RuntimeAttachedResult<SymbolResult> {
        val ensuredRuntime = runtimeManager.ensureRuntime(lifecycleRequest(runtime))
        requireReadCapability(ensuredRuntime.selected, ReadCapability.RESOLVE_SYMBOL)
        return attachedResult(
            payload = rpcClient.post(ensuredRuntime.selected.descriptor, "symbol/resolve", query),
            runtime = ensuredRuntime,
        )
    }

    suspend fun findReferences(
        runtime: RuntimeSelection,
        query: ReferencesQuery,
    ): RuntimeAttachedResult<ReferencesResult> {
        val ensuredRuntime = runtimeManager.ensureRuntime(lifecycleRequest(runtime))
        requireReadCapability(ensuredRuntime.selected, ReadCapability.FIND_REFERENCES)
        return attachedResult(
            payload = rpcClient.post(ensuredRuntime.selected.descriptor, "references", query),
            runtime = ensuredRuntime,
        )
    }

    suspend fun callHierarchy(
        runtime: RuntimeSelection,
        query: CallHierarchyQuery,
    ): RuntimeAttachedResult<CallHierarchyResult> {
        val ensuredRuntime = runtimeManager.ensureRuntime(lifecycleRequest(runtime))
        requireReadCapability(ensuredRuntime.selected, ReadCapability.CALL_HIERARCHY)
        return attachedResult(
            payload = rpcClient.post(ensuredRuntime.selected.descriptor, "call-hierarchy", query),
            runtime = ensuredRuntime,
        )
    }

    suspend fun typeHierarchy(
        runtime: RuntimeSelection,
        query: TypeHierarchyQuery,
    ): RuntimeAttachedResult<TypeHierarchyResult> {
        val ensuredRuntime = runtimeManager.ensureRuntime(lifecycleRequest(runtime))
        requireReadCapability(ensuredRuntime.selected, ReadCapability.TYPE_HIERARCHY)
        return attachedResult(
            payload = rpcClient.post(ensuredRuntime.selected.descriptor, "type-hierarchy", query),
            runtime = ensuredRuntime,
        )
    }

    suspend fun diagnostics(
        runtime: RuntimeSelection,
        query: DiagnosticsQuery,
    ): RuntimeAttachedResult<DiagnosticsResult> {
        val ensuredRuntime = runtimeManager.ensureRuntime(lifecycleRequest(runtime))
        requireReadCapability(ensuredRuntime.selected, ReadCapability.DIAGNOSTICS)
        return attachedResult(
            payload = rpcClient.post(ensuredRuntime.selected.descriptor, "diagnostics", query),
            runtime = ensuredRuntime,
        )
    }

    suspend fun fileOutline(
        runtime: RuntimeSelection,
        query: FileOutlineQuery,
    ): RuntimeAttachedResult<FileOutlineResult> {
        val ensuredRuntime = runtimeManager.ensureRuntime(lifecycleRequest(runtime))
        requireReadCapability(ensuredRuntime.selected, ReadCapability.FILE_OUTLINE)
        return attachedResult(
            payload = rpcClient.post(ensuredRuntime.selected.descriptor, "file-outline", query),
            runtime = ensuredRuntime,
        )
    }

    suspend fun workspaceSymbolSearch(
        runtime: RuntimeSelection,
        query: WorkspaceSymbolQuery,
    ): RuntimeAttachedResult<WorkspaceSymbolResult> {
        val ensuredRuntime = runtimeManager.ensureRuntime(lifecycleRequest(runtime))
        requireReadCapability(ensuredRuntime.selected, ReadCapability.WORKSPACE_SYMBOL_SEARCH)
        return attachedResult(
            payload = rpcClient.post(ensuredRuntime.selected.descriptor, "workspace-symbol", query),
            runtime = ensuredRuntime,
        )
    }

    suspend fun workspaceFiles(
        runtime: RuntimeSelection,
        query: WorkspaceFilesQuery,
    ): RuntimeAttachedResult<WorkspaceFilesResult> {
        val ensuredRuntime = runtimeManager.ensureRuntime(lifecycleRequest(runtime))
        requireReadCapability(ensuredRuntime.selected, ReadCapability.WORKSPACE_FILES)
        return attachedResult(
            payload = rpcClient.post(ensuredRuntime.selected.descriptor, "workspace/files", query),
            runtime = ensuredRuntime,
        )
    }

    suspend fun implementations(
        runtime: RuntimeSelection,
        query: ImplementationsQuery,
    ): RuntimeAttachedResult<ImplementationsResult> {
        val ensuredRuntime = runtimeManager.ensureRuntime(lifecycleRequest(runtime))
        requireReadCapability(ensuredRuntime.selected, ReadCapability.IMPLEMENTATIONS)
        return attachedResult(
            payload = rpcClient.post(ensuredRuntime.selected.descriptor, "implementations", query),
            runtime = ensuredRuntime,
        )
    }

    suspend fun codeActions(
        runtime: RuntimeSelection,
        query: CodeActionsQuery,
    ): RuntimeAttachedResult<CodeActionsResult> {
        val ensuredRuntime = runtimeManager.ensureRuntime(lifecycleRequest(runtime))
        requireReadCapability(ensuredRuntime.selected, ReadCapability.CODE_ACTIONS)
        return attachedResult(
            payload = rpcClient.post(ensuredRuntime.selected.descriptor, "code-actions", query),
            runtime = ensuredRuntime,
        )
    }

    suspend fun completions(
        runtime: RuntimeSelection,
        query: CompletionsQuery,
    ): RuntimeAttachedResult<CompletionsResult> {
        val ensuredRuntime = runtimeManager.ensureRuntime(lifecycleRequest(runtime))
        requireReadCapability(ensuredRuntime.selected, ReadCapability.COMPLETIONS)
        return attachedResult(
            payload = rpcClient.post(ensuredRuntime.selected.descriptor, "completions", query),
            runtime = ensuredRuntime,
        )
    }

    suspend fun semanticInsertionPoint(
        runtime: RuntimeSelection,
        query: SemanticInsertionQuery,
    ): RuntimeAttachedResult<SemanticInsertionResult> {
        val ensuredRuntime = runtimeManager.ensureRuntime(lifecycleRequest(runtime))
        requireReadCapability(ensuredRuntime.selected, ReadCapability.SEMANTIC_INSERTION_POINT)
        return attachedResult(
            payload = rpcClient.post(ensuredRuntime.selected.descriptor, "semantic-insertion-point", query),
            runtime = ensuredRuntime,
        )
    }

    suspend fun rename(
        runtime: RuntimeSelection,
        query: RenameQuery,
    ): RuntimeAttachedResult<RenameResult> {
        val ensuredRuntime = runtimeManager.ensureRuntime(lifecycleRequest(runtime))
        requireMutationCapability(ensuredRuntime.selected, MutationCapability.RENAME)
        return attachedResult(
            payload = rpcClient.post(ensuredRuntime.selected.descriptor, "rename", query),
            runtime = ensuredRuntime,
        )
    }

    suspend fun optimizeImports(
        runtime: RuntimeSelection,
        query: ImportOptimizeQuery,
    ): RuntimeAttachedResult<ImportOptimizeResult> {
        val ensuredRuntime = runtimeManager.ensureRuntime(lifecycleRequest(runtime))
        requireMutationCapability(ensuredRuntime.selected, MutationCapability.OPTIMIZE_IMPORTS)
        return attachedResult(
            payload = rpcClient.post(ensuredRuntime.selected.descriptor, "imports/optimize", query),
            runtime = ensuredRuntime,
        )
    }

    fun install(options: InstallOptions): InstallResult = installService.install(options)

    fun installSkill(options: InstallSkillOptions): InstallSkillResult = installSkillService.install(options)

    fun smoke(options: SmokeOptions): CliExternalProcess = smokeCommandSupport.plan(options)

    suspend fun applyEdits(
        runtime: RuntimeSelection,
        query: ApplyEditsQuery,
    ): RuntimeAttachedResult<ApplyEditsResult> {
        val ensuredRuntime = runtimeManager.ensureRuntime(lifecycleRequest(runtime))
        requireMutationCapability(ensuredRuntime.selected, MutationCapability.APPLY_EDITS)
        if (query.fileOperations.isNotEmpty()) {
            requireMutationCapability(ensuredRuntime.selected, MutationCapability.FILE_OPERATIONS)
        }
        return attachedResult(
            payload = rpcClient.post(ensuredRuntime.selected.descriptor, "edits/apply", query),
            runtime = ensuredRuntime,
        )
    }

    private fun lifecycleRequest(runtime: RuntimeSelection): RuntimeLifecycleRequest =
        RuntimeLifecycleRequest(selection = runtime)

    private fun <T> attachedResult(
        payload: T,
        runtime: WorkspaceEnsureResult,
    ): RuntimeAttachedResult<T> = RuntimeAttachedResult(
        payload = payload,
        runtime = runtime.selected,
        daemonNote = runtime.note,
    )

    private fun requireReadCapability(
        candidate: RuntimeCandidateStatus,
        capability: ReadCapability,
    ) {
        val capabilities = candidate.capabilities
            ?: throw CliFailure(
                code = "CAPABILITIES_UNAVAILABLE",
                message = "Capabilities are unavailable for ${candidate.descriptor.backendName}",
            )
        if (!capabilities.readCapabilities.contains(capability)) {
            throw CapabilityNotSupportedException(
                capability = capability.name,
                message = "${candidate.descriptor.backendName} does not advertise $capability",
            )
        }
    }

    private fun requireMutationCapability(
        candidate: RuntimeCandidateStatus,
        capability: MutationCapability,
    ) {
        val capabilities = candidate.capabilities
            ?: throw CliFailure(
                code = "CAPABILITIES_UNAVAILABLE",
                message = "Capabilities are unavailable for ${candidate.descriptor.backendName}",
            )
        if (!capabilities.mutationCapabilities.contains(capability)) {
            throw CapabilityNotSupportedException(
                capability = capability.name,
                message = "${candidate.descriptor.backendName} does not advertise $capability",
            )
        }
    }
}
