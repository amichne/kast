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
import kotlinx.serialization.json.Json

internal class CliService(
    json: Json,
    private val installService: InstallService = InstallService(),
    private val installSkillService: InstallSkillService = InstallSkillService(),
) {
    private val rpcClient = KastRpcClient(json)
    private val runtimeManager = WorkspaceRuntimeManager(rpcClient)
    private val smokeCommandSupport: SmokeCommandSupport = SmokeCommandSupport(runtimeManager)

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
        return attachedResult(
            payload = rpcClient.post(runtime.selected.descriptor, "workspace/refresh", query),
            runtime = runtime,
        )
    }

    suspend fun workspaceStop(options: RuntimeCommandOptions): DaemonStopResult =
        runtimeManager.workspaceStop(options)

    suspend fun capabilities(options: RuntimeCommandOptions): RuntimeAttachedResult<BackendCapabilities> {
        val runtime = runtimeManager.ensureRuntime(options)
        val capabilities = checkNotNull(runtime.selected.capabilities) {
            "Runtime capabilities were not loaded after ensure for ${runtime.selected.descriptor.backendName}"
        }
        return attachedResult(
            payload = capabilities,
            runtime = runtime,
        )
    }

    suspend fun resolveSymbol(
        options: RuntimeCommandOptions,
        query: SymbolQuery,
    ): RuntimeAttachedResult<SymbolResult> {
        val runtime = runtimeManager.ensureRuntime(options)
        requireReadCapability(runtime.selected, ReadCapability.RESOLVE_SYMBOL)
        return attachedResult(
            payload = rpcClient.post(runtime.selected.descriptor, "symbol/resolve", query),
            runtime = runtime,
        )
    }

    suspend fun findReferences(
        options: RuntimeCommandOptions,
        query: ReferencesQuery,
    ): RuntimeAttachedResult<ReferencesResult> {
        val runtime = runtimeManager.ensureRuntime(options)
        requireReadCapability(runtime.selected, ReadCapability.FIND_REFERENCES)
        return attachedResult(
            payload = rpcClient.post(runtime.selected.descriptor, "references", query),
            runtime = runtime,
        )
    }

    suspend fun callHierarchy(
        options: RuntimeCommandOptions,
        query: CallHierarchyQuery,
    ): RuntimeAttachedResult<CallHierarchyResult> {
        val runtime = runtimeManager.ensureRuntime(options)
        requireReadCapability(runtime.selected, ReadCapability.CALL_HIERARCHY)
        return attachedResult(
            payload = rpcClient.post(runtime.selected.descriptor, "call-hierarchy", query),
            runtime = runtime,
        )
    }

    suspend fun typeHierarchy(
        options: RuntimeCommandOptions,
        query: TypeHierarchyQuery,
    ): RuntimeAttachedResult<TypeHierarchyResult> {
        val runtime = runtimeManager.ensureRuntime(options)
        requireReadCapability(runtime.selected, ReadCapability.TYPE_HIERARCHY)
        return attachedResult(
            payload = rpcClient.post(runtime.selected.descriptor, "type-hierarchy", query),
            runtime = runtime,
        )
    }

    suspend fun diagnostics(
        options: RuntimeCommandOptions,
        query: DiagnosticsQuery,
    ): RuntimeAttachedResult<DiagnosticsResult> {
        val runtime = runtimeManager.ensureRuntime(options)
        requireReadCapability(runtime.selected, ReadCapability.DIAGNOSTICS)
        return attachedResult(
            payload = rpcClient.post(runtime.selected.descriptor, "diagnostics", query),
            runtime = runtime,
        )
    }

    suspend fun fileOutline(
        options: RuntimeCommandOptions,
        query: FileOutlineQuery,
    ): RuntimeAttachedResult<FileOutlineResult> {
        val runtime = runtimeManager.ensureRuntime(options)
        requireReadCapability(runtime.selected, ReadCapability.FILE_OUTLINE)
        return attachedResult(
            payload = rpcClient.post(runtime.selected.descriptor, "file-outline", query),
            runtime = runtime,
        )
    }

    suspend fun workspaceSymbolSearch(
        options: RuntimeCommandOptions,
        query: WorkspaceSymbolQuery,
    ): RuntimeAttachedResult<WorkspaceSymbolResult> {
        val runtime = runtimeManager.ensureRuntime(options)
        requireReadCapability(runtime.selected, ReadCapability.WORKSPACE_SYMBOL_SEARCH)
        return attachedResult(
            payload = rpcClient.post(runtime.selected.descriptor, "workspace-symbol", query),
            runtime = runtime,
        )
    }

    suspend fun workspaceFiles(
        options: RuntimeCommandOptions,
        query: WorkspaceFilesQuery,
    ): RuntimeAttachedResult<WorkspaceFilesResult> {
        val runtime = runtimeManager.ensureRuntime(options)
        requireReadCapability(runtime.selected, ReadCapability.WORKSPACE_FILES)
        return attachedResult(
            payload = rpcClient.post(runtime.selected.descriptor, "workspace/files", query),
            runtime = runtime,
        )
    }

    suspend fun implementations(
        options: RuntimeCommandOptions,
        query: ImplementationsQuery,
    ): RuntimeAttachedResult<ImplementationsResult> {
        val runtime = runtimeManager.ensureRuntime(options)
        requireReadCapability(runtime.selected, ReadCapability.IMPLEMENTATIONS)
        return attachedResult(
            payload = rpcClient.post(runtime.selected.descriptor, "implementations", query),
            runtime = runtime,
        )
    }

    suspend fun codeActions(
        options: RuntimeCommandOptions,
        query: CodeActionsQuery,
    ): RuntimeAttachedResult<CodeActionsResult> {
        val runtime = runtimeManager.ensureRuntime(options)
        requireReadCapability(runtime.selected, ReadCapability.CODE_ACTIONS)
        return attachedResult(
            payload = rpcClient.post(runtime.selected.descriptor, "code-actions", query),
            runtime = runtime,
        )
    }

    suspend fun completions(
        options: RuntimeCommandOptions,
        query: CompletionsQuery,
    ): RuntimeAttachedResult<CompletionsResult> {
        val runtime = runtimeManager.ensureRuntime(options)
        requireReadCapability(runtime.selected, ReadCapability.COMPLETIONS)
        return attachedResult(
            payload = rpcClient.post(runtime.selected.descriptor, "completions", query),
            runtime = runtime,
        )
    }

    suspend fun semanticInsertionPoint(
        options: RuntimeCommandOptions,
        query: SemanticInsertionQuery,
    ): RuntimeAttachedResult<SemanticInsertionResult> {
        val runtime = runtimeManager.ensureRuntime(options)
        requireReadCapability(runtime.selected, ReadCapability.SEMANTIC_INSERTION_POINT)
        return attachedResult(
            payload = rpcClient.post(runtime.selected.descriptor, "semantic-insertion-point", query),
            runtime = runtime,
        )
    }

    suspend fun rename(
        options: RuntimeCommandOptions,
        query: RenameQuery,
    ): RuntimeAttachedResult<RenameResult> {
        val runtime = runtimeManager.ensureRuntime(options)
        requireMutationCapability(runtime.selected, MutationCapability.RENAME)
        return attachedResult(
            payload = rpcClient.post(runtime.selected.descriptor, "rename", query),
            runtime = runtime,
        )
    }

    suspend fun optimizeImports(
        options: RuntimeCommandOptions,
        query: ImportOptimizeQuery,
    ): RuntimeAttachedResult<ImportOptimizeResult> {
        val runtime = runtimeManager.ensureRuntime(options)
        requireMutationCapability(runtime.selected, MutationCapability.OPTIMIZE_IMPORTS)
        return attachedResult(
            payload = rpcClient.post(runtime.selected.descriptor, "imports/optimize", query),
            runtime = runtime,
        )
    }

    fun install(options: InstallOptions): InstallResult = installService.install(options)

    fun installSkill(options: InstallSkillOptions): InstallSkillResult = installSkillService.install(options)

    suspend fun smoke(options: SmokeOptions): SmokeReport = smokeCommandSupport.run(options)

    fun daemonStart(options: DaemonStartOptions): CliOutput {
        val runtimeLibsDir = options.runtimeLibsDir
            ?: System.getenv("KAST_STANDALONE_RUNTIME_LIBS")
                ?.takeIf(String::isNotBlank)
                ?.let { java.nio.file.Path.of(it) }
            ?: System.getenv("KAST_INSTALL_ROOT")
                ?.takeIf(String::isNotBlank)
                ?.let { java.nio.file.Path.of(it).resolve("backends/current/runtime-libs") }
            ?: throw CliFailure(
                code = "DAEMON_START_ERROR",
                message = "Cannot locate backend runtime-libs. " +
                    "Set KAST_STANDALONE_RUNTIME_LIBS=/path/to/runtime-libs or install the backend with " +
                    "`./kast.sh install --components=backend`.",
            )

        val classpathFile = runtimeLibsDir.resolve("classpath.txt")
        if (!java.nio.file.Files.isRegularFile(classpathFile)) {
            throw CliFailure(
                code = "DAEMON_START_ERROR",
                message = "Backend runtime-libs classpath not found at $classpathFile. " +
                    "Reinstall with `./kast.sh install --components=backend` or set KAST_STANDALONE_RUNTIME_LIBS.",
            )
        }

        val entries = classpathFile.toFile().useLines { lines ->
            lines.filter(String::isNotBlank).toList()
        }
        if (entries.isEmpty()) {
            throw CliFailure(
                code = "DAEMON_START_ERROR",
                message = "Backend classpath.txt is empty at $classpathFile.",
            )
        }

        val pathSeparator = System.getProperty("path.separator", ":")
        val classpath = entries.joinToString(pathSeparator) { entry ->
            runtimeLibsDir.resolve(entry).toString()
        }

        val javaExec = System.getenv("JAVA_HOME")
            ?.takeIf(String::isNotBlank)
            ?.let { "$it/bin/java" }
            ?: "java"

        val command = buildList {
            add(javaExec)
            // JAVA_OPTS is treated as whitespace-separated tokens (no support for quoted spaces)
            System.getenv("JAVA_OPTS")?.takeIf(String::isNotBlank)?.let { addAll(it.trim().split(Regex("\\s+"))) }
            add("-cp")
            add(classpath)
            add("io.github.amichne.kast.standalone.StandaloneMainKt")
            addAll(options.standaloneArgs)
        }

        return CliOutput.ExternalProcess(
            CliExternalProcess(command = command),
        )
    }

    suspend fun applyEdits(
        options: RuntimeCommandOptions,
        query: ApplyEditsQuery,
    ): RuntimeAttachedResult<ApplyEditsResult> {
        val runtime = runtimeManager.ensureRuntime(options)
        requireMutationCapability(runtime.selected, MutationCapability.APPLY_EDITS)
        if (query.fileOperations.isNotEmpty()) {
            requireMutationCapability(runtime.selected, MutationCapability.FILE_OPERATIONS)
        }
        return attachedResult(
            payload = rpcClient.post(runtime.selected.descriptor, "edits/apply", query),
            runtime = runtime,
        )
    }

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
