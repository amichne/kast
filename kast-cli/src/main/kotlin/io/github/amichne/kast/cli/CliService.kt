package io.github.amichne.kast.cli

import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.BackendCapabilities
import io.github.amichne.kast.api.contract.query.CallHierarchyQuery
import io.github.amichne.kast.api.contract.result.CallHierarchyResult
import io.github.amichne.kast.api.protocol.CapabilityNotSupportedException
import io.github.amichne.kast.api.contract.query.CodeActionsQuery
import io.github.amichne.kast.api.contract.result.CodeActionsResult
import io.github.amichne.kast.api.contract.query.CompletionsQuery
import io.github.amichne.kast.api.contract.result.CompletionsResult
import io.github.amichne.kast.api.contract.query.DiagnosticsQuery
import io.github.amichne.kast.api.contract.result.DiagnosticsResult
import io.github.amichne.kast.api.contract.query.FileOutlineQuery
import io.github.amichne.kast.api.contract.result.FileOutlineResult
import io.github.amichne.kast.api.contract.query.ImportOptimizeQuery
import io.github.amichne.kast.api.contract.result.ImportOptimizeResult
import io.github.amichne.kast.api.contract.query.ImplementationsQuery
import io.github.amichne.kast.api.contract.result.ImplementationsResult
import io.github.amichne.kast.api.contract.MutationCapability
import io.github.amichne.kast.api.contract.ReadCapability
import io.github.amichne.kast.api.contract.query.RefreshQuery
import io.github.amichne.kast.api.contract.result.RefreshResult
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.result.ReferencesResult
import io.github.amichne.kast.api.contract.query.RenameQuery
import io.github.amichne.kast.api.contract.result.RenameResult
import io.github.amichne.kast.api.contract.SemanticInsertionQuery
import io.github.amichne.kast.api.contract.SemanticInsertionResult
import io.github.amichne.kast.api.contract.query.SymbolQuery
import io.github.amichne.kast.api.contract.result.SymbolResult
import io.github.amichne.kast.api.contract.query.TypeHierarchyQuery
import io.github.amichne.kast.api.contract.result.TypeHierarchyResult
import io.github.amichne.kast.api.contract.query.WorkspaceFilesQuery
import io.github.amichne.kast.api.contract.result.WorkspaceFilesResult
import io.github.amichne.kast.api.contract.query.WorkspaceSymbolQuery
import io.github.amichne.kast.api.contract.result.WorkspaceSymbolResult
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.kastConfigHome
import kotlinx.serialization.json.Json
import java.nio.file.Files

internal class CliService(
    json: Json,
    private val installService: InstallService = InstallService(),
    private val installSkillService: InstallSkillService = InstallSkillService(),
    private val configLoader: (java.nio.file.Path) -> KastConfig = KastConfig::load,
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
        val config = configLoader(options.workspaceRoot)
        val runtimeLibsDir = options.runtimeLibsDir
            ?: config.backends.standalone.runtimeLibsDir
                ?.takeIf(String::isNotBlank)
                ?.let { java.nio.file.Path.of(it).toAbsolutePath().normalize() }
            ?: throw CliFailure(
                code = "DAEMON_START_ERROR",
                message = "Cannot locate backend runtime-libs. " +
                    "Set backends.standalone.runtimeLibsDir in `kast config init` output or pass --runtime-libs-dir.",
            )

        val classpathFile = runtimeLibsDir.resolve("classpath.txt")
        if (!java.nio.file.Files.isRegularFile(classpathFile)) {
            throw CliFailure(
                code = "DAEMON_START_ERROR",
                message = "Backend runtime-libs classpath not found at $classpathFile. " +
                    "Reinstall the backend, update backends.standalone.runtimeLibsDir, or pass --runtime-libs-dir.",
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

    fun configInit(): CliOutput {
        val configFile = kastConfigHome().resolve("config.toml")
        Files.createDirectories(configFile.parent)
        if (!Files.exists(configFile)) {
            Files.writeString(configFile, defaultConfigTemplate())
            return CliOutput.Text("Wrote $configFile")
        }
        return CliOutput.Text("Config file already exists at $configFile")
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

private fun defaultConfigTemplate(): String = """
    # Kast configuration
    # Uncomment settings to override defaults.

    # [server]
    # maxResults = 500
    # requestTimeoutMillis = 30000
    # maxConcurrentRequests = 4

    # [indexing]
    # phase2Enabled = true
    # phase2BatchSize = 50
    # identifierIndexWaitMillis = 10000
    # referenceBatchSize = 50

    # [cache]
    # enabled = true
    # writeDelayMillis = 5000
    # sourceIndexSaveDelayMillis = 5000

    # [watcher]
    # debounceMillis = 200

    # [gradle]
    # toolingApiTimeoutMillis = 60000
    # maxIncludedProjects = 200

    # [telemetry]
    # enabled = false
    # scopes = "all"
    # detail = "basic"
    # outputFile = null

    # [backends.standalone]
    # enabled = true
    # runtimeLibsDir = "/absolute/path/to/runtime-libs"

    # [backends.intellij]
    # enabled = true
""".trimIndent() + System.lineSeparator()
