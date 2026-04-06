package io.github.amichne.kast.cli

import io.github.amichne.kast.api.ApplyEditsQuery
import io.github.amichne.kast.api.ApplyEditsResult
import io.github.amichne.kast.api.BackendCapabilities
import io.github.amichne.kast.api.CallHierarchyQuery
import io.github.amichne.kast.api.CallHierarchyResult
import io.github.amichne.kast.api.CapabilityNotSupportedException
import io.github.amichne.kast.api.DiagnosticsQuery
import io.github.amichne.kast.api.DiagnosticsResult
import io.github.amichne.kast.api.ImportOptimizeQuery
import io.github.amichne.kast.api.ImportOptimizeResult
import io.github.amichne.kast.api.MutationCapability
import io.github.amichne.kast.api.ReadCapability
import io.github.amichne.kast.api.RefreshQuery
import io.github.amichne.kast.api.RefreshResult
import io.github.amichne.kast.api.ReferencesQuery
import io.github.amichne.kast.api.ReferencesResult
import io.github.amichne.kast.api.RenameQuery
import io.github.amichne.kast.api.RenameResult
import io.github.amichne.kast.api.SemanticInsertionQuery
import io.github.amichne.kast.api.SemanticInsertionResult
import io.github.amichne.kast.api.SymbolQuery
import io.github.amichne.kast.api.SymbolResult
import io.github.amichne.kast.api.TypeHierarchyQuery
import io.github.amichne.kast.api.TypeHierarchyResult
import kotlinx.serialization.json.Json

internal class CliService(
    json: Json,
    processLauncher: ProcessLauncher,
    private val installService: InstallService = InstallService(),
    private val installSkillService: InstallSkillService = InstallSkillService(),
    private val smokeCommandSupport: SmokeCommandSupport = SmokeCommandSupport(),
) {
    private val rpcClient = KastRpcClient(json)
    private val runtimeManager = WorkspaceRuntimeManager(rpcClient, processLauncher)

    suspend fun workspaceStatus(options: RuntimeCommandOptions): WorkspaceStatusResult =
        runtimeManager.workspaceStatus(options)

    suspend fun workspaceEnsure(options: RuntimeCommandOptions): WorkspaceEnsureResult =
        runtimeManager.workspaceEnsure(options)

    suspend fun workspaceRefresh(
        options: RuntimeCommandOptions,
        query: RefreshQuery,
    ): RuntimeAttachedResult<RefreshResult> {
        val runtime = runtimeManager.ensureRuntime(options)
        requireMutationCapability(runtime.selected, MutationCapability.REFRESH_WORKSPACE)
        return RuntimeAttachedResult(
            payload = rpcClient.post(runtime.selected.descriptor, "workspace/refresh", query),
            runtime = runtime.selected,
        )
    }

    suspend fun daemonStart(options: RuntimeCommandOptions): WorkspaceEnsureResult =
        runtimeManager.daemonStart(options)

    suspend fun daemonStop(options: RuntimeCommandOptions): DaemonStopResult =
        runtimeManager.daemonStop(options)

    suspend fun capabilities(options: RuntimeCommandOptions): RuntimeAttachedResult<BackendCapabilities> {
        val runtime = runtimeManager.ensureRuntime(options)
        val capabilities = checkNotNull(runtime.selected.capabilities) {
            "Runtime capabilities were not loaded after ensure for ${runtime.selected.descriptor.backendName}"
        }
        return RuntimeAttachedResult(
            payload = capabilities,
            runtime = runtime.selected,
        )
    }

    suspend fun resolveSymbol(
        options: RuntimeCommandOptions,
        query: SymbolQuery,
    ): RuntimeAttachedResult<SymbolResult> {
        val runtime = runtimeManager.ensureRuntime(options)
        requireReadCapability(runtime.selected, ReadCapability.RESOLVE_SYMBOL)
        return RuntimeAttachedResult(
            payload = rpcClient.post(runtime.selected.descriptor, "symbol/resolve", query),
            runtime = runtime.selected,
        )
    }

    suspend fun findReferences(
        options: RuntimeCommandOptions,
        query: ReferencesQuery,
    ): RuntimeAttachedResult<ReferencesResult> {
        val runtime = runtimeManager.ensureRuntime(options)
        requireReadCapability(runtime.selected, ReadCapability.FIND_REFERENCES)
        return RuntimeAttachedResult(
            payload = rpcClient.post(runtime.selected.descriptor, "references", query),
            runtime = runtime.selected,
        )
    }

    suspend fun callHierarchy(
        options: RuntimeCommandOptions,
        query: CallHierarchyQuery,
    ): RuntimeAttachedResult<CallHierarchyResult> {
        val runtime = runtimeManager.ensureRuntime(options)
        requireReadCapability(runtime.selected, ReadCapability.CALL_HIERARCHY)
        return RuntimeAttachedResult(
            payload = rpcClient.post(runtime.selected.descriptor, "call-hierarchy", query),
            runtime = runtime.selected,
        )
    }

    suspend fun typeHierarchy(
        options: RuntimeCommandOptions,
        query: TypeHierarchyQuery,
    ): RuntimeAttachedResult<TypeHierarchyResult> {
        val runtime = runtimeManager.ensureRuntime(options)
        requireReadCapability(runtime.selected, ReadCapability.TYPE_HIERARCHY)
        return RuntimeAttachedResult(
            payload = rpcClient.post(runtime.selected.descriptor, "type-hierarchy", query),
            runtime = runtime.selected,
        )
    }

    suspend fun diagnostics(
        options: RuntimeCommandOptions,
        query: DiagnosticsQuery,
    ): RuntimeAttachedResult<DiagnosticsResult> {
        val runtime = runtimeManager.ensureRuntime(options)
        requireReadCapability(runtime.selected, ReadCapability.DIAGNOSTICS)
        return RuntimeAttachedResult(
            payload = rpcClient.post(runtime.selected.descriptor, "diagnostics", query),
            runtime = runtime.selected,
        )
    }

    suspend fun semanticInsertionPoint(
        options: RuntimeCommandOptions,
        query: SemanticInsertionQuery,
    ): RuntimeAttachedResult<SemanticInsertionResult> {
        val runtime = runtimeManager.ensureRuntime(options)
        requireReadCapability(runtime.selected, ReadCapability.SEMANTIC_INSERTION_POINT)
        return RuntimeAttachedResult(
            payload = rpcClient.post(runtime.selected.descriptor, "semantic-insertion-point", query),
            runtime = runtime.selected,
        )
    }

    suspend fun rename(
        options: RuntimeCommandOptions,
        query: RenameQuery,
    ): RuntimeAttachedResult<RenameResult> {
        val runtime = runtimeManager.ensureRuntime(options)
        requireMutationCapability(runtime.selected, MutationCapability.RENAME)
        return RuntimeAttachedResult(
            payload = rpcClient.post(runtime.selected.descriptor, "rename", query),
            runtime = runtime.selected,
        )
    }

    suspend fun optimizeImports(
        options: RuntimeCommandOptions,
        query: ImportOptimizeQuery,
    ): RuntimeAttachedResult<ImportOptimizeResult> {
        val runtime = runtimeManager.ensureRuntime(options)
        requireMutationCapability(runtime.selected, MutationCapability.OPTIMIZE_IMPORTS)
        return RuntimeAttachedResult(
            payload = rpcClient.post(runtime.selected.descriptor, "imports/optimize", query),
            runtime = runtime.selected,
        )
    }

    fun install(options: InstallOptions): InstallResult = installService.install(options)

    fun installSkill(options: InstallSkillOptions): InstallSkillResult = installSkillService.install(options)

    fun smoke(options: SmokeOptions): CliExternalProcess = smokeCommandSupport.plan(options)

    suspend fun applyEdits(
        options: RuntimeCommandOptions,
        query: ApplyEditsQuery,
    ): RuntimeAttachedResult<ApplyEditsResult> {
        val runtime = runtimeManager.ensureRuntime(options)
        requireMutationCapability(runtime.selected, MutationCapability.APPLY_EDITS)
        if (query.fileOperations.isNotEmpty()) {
            requireMutationCapability(runtime.selected, MutationCapability.FILE_OPERATIONS)
        }
        return RuntimeAttachedResult(
            payload = rpcClient.post(runtime.selected.descriptor, "edits/apply", query),
            runtime = runtime.selected,
        )
    }

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
