package io.github.amichne.kast.cli

import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.CallDirection
import io.github.amichne.kast.api.contract.query.CallHierarchyQuery
import io.github.amichne.kast.api.contract.query.CodeActionsQuery
import io.github.amichne.kast.api.contract.query.CompletionsQuery
import io.github.amichne.kast.api.contract.query.DiagnosticsQuery
import io.github.amichne.kast.api.contract.query.FileOutlineQuery
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.query.ImportOptimizeQuery
import io.github.amichne.kast.api.contract.query.ImplementationsQuery
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.query.RefreshQuery
import io.github.amichne.kast.api.contract.query.RenameQuery
import io.github.amichne.kast.api.contract.SemanticInsertionQuery
import io.github.amichne.kast.api.contract.SemanticInsertionTarget
import io.github.amichne.kast.api.client.StandaloneServerOptions
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.query.SymbolQuery
import io.github.amichne.kast.api.contract.TypeHierarchyDirection
import io.github.amichne.kast.api.contract.query.TypeHierarchyQuery
import io.github.amichne.kast.api.contract.query.WorkspaceFilesQuery
import io.github.amichne.kast.api.contract.query.WorkspaceSymbolQuery
import io.github.amichne.kast.cli.skill.SkillWrapperName
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.readText
internal class CliCommandParser(
    private val json: Json,
) {
    fun parse(args: Array<String>): CliCommand {
        val parsed = ParsedArguments.parse(args)
        if (parsed.positionals.isEmpty() && parsed.options.isEmpty() && parsed.flags.isEmpty()) {
            return CliCommand.Help()
        }
        if (parsed.flags.contains(HELP_FLAG)) {
            return CliCommand.Help(parsed.positionals)
        }
        if (parsed.positionals.firstOrNull() == "help") {
            return CliCommand.Help(parsed.positionals.drop(1))
        }
        if (parsed.flags.contains(VERSION_FLAG) || parsed.positionals == listOf("version")) {
            if (parsed.options.isNotEmpty() || parsed.positionals.size > 1 || (parsed.positionals.isNotEmpty() && parsed.positionals != listOf("version"))) {
                throw CliFailure(
                    code = "CLI_USAGE",
                    message = "--version does not accept additional arguments",
                    details = CliCommandCatalog.topLevelUsageDetails(),
                )
            }
            return CliCommand.Version
        }
        if (parsed.positionals.isEmpty()) {
            throw CliFailure(
                code = "CLI_USAGE",
                message = "A command is required",
                details = CliCommandCatalog.topLevelUsageDetails(),
            )
        }

        if (parsed.positionals.firstOrNull() == "skill") {
            return parseSkillCommand(parsed)
        }

        val metadata = CliCommandCatalog.find(parsed.positionals)
        if (metadata != null) {
            return parseKnownCommand(metadata, parsed)
        }
        if (CliCommandCatalog.commandsUnder(parsed.positionals).isNotEmpty()) {
            return CliCommand.Help(parsed.positionals)
        }

        throw CliFailure(
            code = "CLI_USAGE",
            message = "Unknown command: ${args.joinToString(" ")}",
            details = CliCommandCatalog.unknownCommandDetails(parsed.positionals),
        )
    }

    private fun parseKnownCommand(
        metadata: CliCommandMetadata,
        parsed: ParsedArguments,
    ): CliCommand {
        return try {
            when (metadata.path) {
                listOf("workspace", "status") -> CliCommand.WorkspaceStatus(parsed.runtimeOptions())
                listOf("workspace", "ensure") -> CliCommand.WorkspaceEnsure(parsed.runtimeOptions())
                listOf("workspace", "refresh") -> CliCommand.WorkspaceRefresh(parsed.runtimeOptions(), parsed.refreshQuery(json))
                listOf("workspace", "stop") -> CliCommand.WorkspaceStop(parsed.runtimeOptions())
                listOf("workspace", "files") -> CliCommand.WorkspaceFiles(parsed.runtimeOptions(), parsed.workspaceFilesQuery(json))
                listOf("completion", "bash") -> CliCommand.Completion(CliCompletionShell.BASH)
                listOf("completion", "zsh") -> CliCommand.Completion(CliCompletionShell.ZSH)
                listOf("capabilities") -> CliCommand.Capabilities(parsed.runtimeOptions())
                listOf("resolve") -> CliCommand.ResolveSymbol(parsed.runtimeOptions(), parsed.symbolQuery(json))
                listOf("references") -> CliCommand.FindReferences(parsed.runtimeOptions(), parsed.referencesQuery(json))
                listOf("call-hierarchy") -> CliCommand.CallHierarchy(parsed.runtimeOptions(), parsed.callHierarchyQuery(json))
                listOf("type-hierarchy") -> CliCommand.TypeHierarchy(
                    parsed.withoutOption("max-results").runtimeOptions(),
                    parsed.typeHierarchyQuery(json),
                )
                listOf("insertion-point") -> CliCommand.SemanticInsertionPoint(
                    parsed.runtimeOptions(),
                    parsed.semanticInsertionQuery(json),
                )
                listOf("diagnostics") -> CliCommand.Diagnostics(parsed.runtimeOptions(), parsed.diagnosticsQuery(json))
                listOf("outline") -> CliCommand.FileOutline(parsed.runtimeOptions(), parsed.fileOutlineQuery(json))
                listOf("workspace-symbol") -> CliCommand.WorkspaceSymbol(parsed.withoutOption("max-results").runtimeOptions(), parsed.workspaceSymbolQuery(json))
                listOf("implementations") -> CliCommand.Implementations(parsed.withoutOption("max-results").runtimeOptions(), parsed.implementationsQuery(json))
                listOf("code-actions") -> CliCommand.CodeActions(parsed.runtimeOptions(), parsed.codeActionsQuery(json))
                listOf("completions") -> CliCommand.Completions(parsed.withoutOption("max-results").runtimeOptions(), parsed.completionsQuery(json))
                listOf("rename") -> CliCommand.Rename(parsed.runtimeOptions(), parsed.renameQuery(json))
                listOf("optimize-imports") -> CliCommand.ImportOptimize(
                    parsed.runtimeOptions(),
                    parsed.importOptimizeQuery(json),
                )
                listOf("apply-edits") -> CliCommand.ApplyEdits(parsed.runtimeOptions(), parsed.applyEditsQuery(json))
                listOf("install") -> CliCommand.Install(parsed.installOptions())
                listOf("install", "skill") -> CliCommand.InstallSkill(parsed.installSkillOptions())
                listOf("smoke") -> CliCommand.Smoke(parsed.smokeOptions())
                listOf("daemon", "start") -> CliCommand.DaemonStart(parsed.daemonStartOptions())
                listOf("config", "init") -> CliCommand.ConfigInit
                listOf("eval", "skill") -> CliCommand.EvalSkill(parsed.evalSkillOptions())
                listOf("metrics", "fan-in") -> CliCommand.Metrics(
                    subcommand = MetricsSubcommand.FAN_IN,
                    workspaceRoot = parsed.requireWorkspaceRootPath(),
                    limit = parsed.optionalInt("limit") ?: 50,
                )
                listOf("metrics", "fan-out") -> CliCommand.Metrics(
                    subcommand = MetricsSubcommand.FAN_OUT,
                    workspaceRoot = parsed.requireWorkspaceRootPath(),
                    limit = parsed.optionalInt("limit") ?: 50,
                )
                listOf("metrics", "coupling") -> CliCommand.Metrics(
                    subcommand = MetricsSubcommand.COUPLING,
                    workspaceRoot = parsed.requireWorkspaceRootPath(),
                )
                listOf("metrics", "dead-code") -> CliCommand.Metrics(
                    subcommand = MetricsSubcommand.DEAD_CODE,
                    workspaceRoot = parsed.requireWorkspaceRootPath(),
                )
                listOf("metrics", "impact") -> CliCommand.Metrics(
                    subcommand = MetricsSubcommand.IMPACT,
                    workspaceRoot = parsed.requireWorkspaceRootPath(),
                    symbol = parsed.options["symbol"] ?: throw CliFailure(
                        code = "CLI_USAGE",
                        message = "--symbol is required for metrics impact",
                    ),
                    depth = parsed.optionalInt("depth") ?: 3,
                )
                else -> throw CliFailure(
                    code = "CLI_USAGE",
                    message = "Unknown command: ${metadata.commandText}",
                    details = CliCommandCatalog.unknownCommandDetails(metadata.path),
                )
            }
        } catch (failure: CliFailure) {
            if (failure.code != "CLI_USAGE") {
                throw failure
            }
            throw CliFailure(
                code = failure.code,
                message = failure.message,
                details = CliCommandCatalog.usageDetails(metadata.path) + failure.details,
            )
        }
    }

    private fun parseSkillCommand(parsed: ParsedArguments): CliCommand {
        val positionals = parsed.positionals
        if (positionals.size < 2) {
            return CliCommand.Help(listOf("skill"))
        }
        val wrapperCliName = positionals[1]
        val wrapperName = SkillWrapperName.fromCliName(wrapperCliName)
            ?: throw CliFailure(
                code = "CLI_USAGE",
                message = "Unknown skill wrapper: $wrapperCliName. " +
                    "Valid wrappers: ${SkillWrapperName.entries.joinToString { it.cliName }}",
            )
        if (positionals.size < 3) {
            throw CliFailure(
                code = "CLI_USAGE",
                message = "Skill wrapper '$wrapperCliName' requires a JSON argument (literal or file path)",
            )
        }
        val rawInput = positionals[2]
        return CliCommand.Skill(name = wrapperName, rawInput = rawInput)
    }

    private companion object {
        const val HELP_FLAG = "help"
        const val VERSION_FLAG = "version"
    }
}

internal data class ParsedArguments(
    val positionals: List<String>,
    val options: Map<String, String>,
    val flags: Set<String>,
) {
    companion object {
        fun parse(args: Array<String>): ParsedArguments {
            val positionals = mutableListOf<String>()
            val options = linkedMapOf<String, String>()
            val flags = linkedSetOf<String>()
            args.forEach { argument ->
                when (argument) {
                    "--help", "-h" -> flags += "help"
                    "--version", "-V" -> flags += "version"
                    else -> {
                        if (argument.startsWith("--")) {
                            val parts = argument.removePrefix("--").split("=", limit = 2)
                            if (parts.size != 2 || parts[0].isBlank()) {
                                throw CliFailure(
                                    code = "CLI_USAGE",
                                    message = "Arguments must use --key=value syntax: $argument",
                                    details = CliCommandCatalog.topLevelUsageDetails(),
                                )
                            }
                            options[parts[0]] = parts[1]
                        } else if (argument.startsWith("-")) {
                            throw CliFailure(
                                code = "CLI_USAGE",
                                message = "Unknown flag: $argument",
                                details = CliCommandCatalog.topLevelUsageDetails(),
                            )
                        } else {
                            positionals += argument
                        }
                    }
                }
            }
            return ParsedArguments(
                positionals = positionals,
                options = options,
                flags = flags,
            )
        }
    }

    fun runtimeOptions(backendName: String? = options["backend-name"]): RuntimeCommandOptions {
        val standaloneOptions = StandaloneServerOptions.fromValues(options)
        val requestedBackendName = backendName
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        if (requestedBackendName != null && requestedBackendName !in VALID_BACKEND_NAMES) {
            throw CliFailure(
                code = "CLI_USAGE",
                message = "Unsupported --backend-name=$requestedBackendName. " +
                    "Valid values: ${VALID_BACKEND_NAMES.joinToString()}",
            )
        }
        return RuntimeCommandOptions(
            workspaceRoot = standaloneOptions.workspaceRoot,
            backendName = requestedBackendName,
            waitTimeoutMillis = options["wait-timeout-ms"]?.toLongOrNull() ?: 60_000L,
            standaloneOptions = standaloneOptions,
            acceptIndexing = optionalBoolean("accept-indexing", false),
            noAutoStart = optionalBoolean("no-auto-start", false),
        )
    }

    fun symbolQuery(json: Json): SymbolQuery = requestOrFile(
        serializer = SymbolQuery.serializer(),
        requestFileKey = "request-file",
        json = json,
    ) {
        SymbolQuery(
            position = FilePosition(
                filePath = absoluteFilePath(requireOption("file-path")),
                offset = requireInt("offset"),
            ),
            includeDeclarationScope = optionalBoolean("include-body", false),
            includeDocumentation = optionalBoolean("include-documentation", false),
        )
    }

    fun referencesQuery(json: Json): ReferencesQuery = requestOrFile(
        serializer = ReferencesQuery.serializer(),
        requestFileKey = "request-file",
        json = json,
    ) {
        ReferencesQuery(
            position = FilePosition(
                filePath = absoluteFilePath(requireOption("file-path")),
                offset = requireInt("offset"),
            ),
            includeDeclaration = optionalBoolean("include-declaration", false),
        )
    }

    fun diagnosticsQuery(json: Json): DiagnosticsQuery = requestOrFile(
        serializer = DiagnosticsQuery.serializer(),
        requestFileKey = "request-file",
        json = json,
    ) {
        DiagnosticsQuery(
            filePaths = requireOption("file-paths")
                .split(",")
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map(::absoluteFilePath),
        )
    }

    fun callHierarchyQuery(json: Json): CallHierarchyQuery = requestOrFile(
        serializer = CallHierarchyQuery.serializer(),
        requestFileKey = "request-file",
        json = json,
    ) {
        CallHierarchyQuery(
            position = FilePosition(
                filePath = absoluteFilePath(requireOption("file-path")),
                offset = requireInt("offset"),
            ),
            direction = requireCallDirection("direction"),
            depth = optionalInt("depth", 3),
            maxTotalCalls = optionalInt("max-total-calls", 256),
            maxChildrenPerNode = optionalInt("max-children-per-node", 64),
            timeoutMillis = options["timeout-millis"]?.toLongOrNull(),
        )
    }

    fun typeHierarchyQuery(json: Json): TypeHierarchyQuery = requestOrFile(
        serializer = TypeHierarchyQuery.serializer(),
        requestFileKey = "request-file",
        json = json,
    ) {
        TypeHierarchyQuery(
            position = FilePosition(
                filePath = absoluteFilePath(requireOption("file-path")),
                offset = requireInt("offset"),
            ),
            direction = requireTypeHierarchyDirection("direction"),
            depth = optionalInt("depth", 3),
            maxResults = optionalInt("max-results", 256),
        )
    }

    fun semanticInsertionQuery(json: Json): SemanticInsertionQuery = requestOrFile(
        serializer = SemanticInsertionQuery.serializer(),
        requestFileKey = "request-file",
        json = json,
    ) {
        SemanticInsertionQuery(
            position = FilePosition(
                filePath = absoluteFilePath(requireOption("file-path")),
                offset = requireInt("offset"),
            ),
            target = requireSemanticInsertionTarget("target"),
        )
    }

    fun renameQuery(json: Json): RenameQuery = requestOrFile(
        serializer = RenameQuery.serializer(),
        requestFileKey = "request-file",
        json = json,
    ) {
        RenameQuery(
            position = FilePosition(
                filePath = absoluteFilePath(requireOption("file-path")),
                offset = requireInt("offset"),
            ),
            newName = requireOption("new-name"),
            dryRun = optionalBoolean("dry-run", true),
        )
    }

    fun applyEditsQuery(json: Json): ApplyEditsQuery = requestOrFile(
        serializer = ApplyEditsQuery.serializer(),
        requestFileKey = "request-file",
        json = json,
    ) {
        throw CliFailure(
            code = "CLI_USAGE",
            message = "`apply-edits` requires --request-file=/absolute/path/to/query.json",
        )
    }

    fun importOptimizeQuery(json: Json): ImportOptimizeQuery = requestOrFile(
        serializer = ImportOptimizeQuery.serializer(),
        requestFileKey = "request-file",
        json = json,
    ) {
        ImportOptimizeQuery(
            filePaths = requireOption("file-paths")
                .split(",")
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map(::absoluteFilePath),
        )
    }

    fun workspaceFilesQuery(json: Json): WorkspaceFilesQuery = requestOrFile(
        serializer = WorkspaceFilesQuery.serializer(),
        requestFileKey = "request-file",
        json = json,
    ) {
        WorkspaceFilesQuery(
            moduleName = options["module-name"],
            includeFiles = optionalBoolean("include-files", false),
            maxFilesPerModule = options["max-files-per-module"]?.toIntOrNull(),
        )
    }

    fun implementationsQuery(json: Json): ImplementationsQuery = requestOrFile(
        serializer = ImplementationsQuery.serializer(),
        requestFileKey = "request-file",
        json = json,
    ) {
        ImplementationsQuery(
            position = FilePosition(
                filePath = absoluteFilePath(requireOption("file-path")),
                offset = requireInt("offset"),
            ),
            maxResults = optionalInt("max-results", 100),
        )
    }

    fun codeActionsQuery(json: Json): CodeActionsQuery = requestOrFile(
        serializer = CodeActionsQuery.serializer(),
        requestFileKey = "request-file",
        json = json,
    ) {
        CodeActionsQuery(
            position = FilePosition(
                filePath = absoluteFilePath(requireOption("file-path")),
                offset = requireInt("offset"),
            ),
            diagnosticCode = options["diagnostic-code"]?.takeIf { it.isNotBlank() },
        )
    }

    fun completionsQuery(json: Json): CompletionsQuery = requestOrFile(
        serializer = CompletionsQuery.serializer(),
        requestFileKey = "request-file",
        json = json,
    ) {
        CompletionsQuery(
            position = FilePosition(
                filePath = absoluteFilePath(requireOption("file-path")),
                offset = requireInt("offset"),
            ),
            maxResults = optionalInt("max-results", 100),
            kindFilter = options["kind-filter"]
                ?.split(",")
                ?.map(String::trim)
                ?.filter(String::isNotBlank)
                ?.map { raw ->
                    SymbolKind.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                        ?: throw CliFailure(
                            code = "CLI_USAGE",
                            message = "Unknown symbol kind in --kind-filter: $raw. " +
                                "Valid values: ${SymbolKind.entries.joinToString { it.name }}",
                        )
                }
                ?.toSet()
                ?.takeIf { it.isNotEmpty() },
        )
    }

    fun refreshQuery(json: Json): RefreshQuery = requestOrFile(
        serializer = RefreshQuery.serializer(),
        requestFileKey = "request-file",
        json = json,
    ) {
        RefreshQuery(
            filePaths = options["file-paths"]
                ?.split(",")
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.map(::absoluteFilePath)
                .orEmpty(),
        )
    }

    fun fileOutlineQuery(json: Json): FileOutlineQuery = requestOrFile(
        serializer = FileOutlineQuery.serializer(),
        requestFileKey = "request-file",
        json = json,
    ) {
        FileOutlineQuery(
            filePath = absoluteFilePath(requireOption("file-path")),
        )
    }

    fun workspaceSymbolQuery(json: Json): WorkspaceSymbolQuery = requestOrFile(
        serializer = WorkspaceSymbolQuery.serializer(),
        requestFileKey = "request-file",
        json = json,
    ) {
        WorkspaceSymbolQuery(
            pattern = requireOption("pattern"),
            kind = options["kind"]?.let { raw ->
                SymbolKind.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                    ?: throw CliFailure(
                        code = "CLI_USAGE",
                        message = "Unknown symbol kind: $raw. Valid values: ${SymbolKind.entries.joinToString { it.name }}",
                    )
            },
            maxResults = optionalInt("max-results", 100),
            regex = optionalBoolean("regex", false),
            includeDeclarationScope = optionalBoolean("include-body", false),
        )
    }

    fun installSkillOptions(): InstallSkillOptions = InstallSkillOptions(
        targetDir = options["target-dir"]?.let { Path.of(it).toAbsolutePath().normalize() },
        name = options["name"]?.takeIf(String::isNotEmpty)
            ?: options["link-name"]?.takeIf(String::isNotEmpty)
            ?: "kast",
        force = optionalBoolean("yes", false),
    )

    fun installOptions(): InstallOptions {
        val home = Path.of(System.getProperty("user.home"))
        return InstallOptions(
            archivePath = Path.of(requireOption("archive")).toAbsolutePath().normalize(),
            instanceName = options["instance"]?.takeIf(String::isNotEmpty),
            instancesRoot = options["instances-root"]
                ?.let { Path.of(it).toAbsolutePath().normalize() }
                ?: home.resolve(".local/share/kast/instances"),
            binDir = options["bin-dir"]
                ?.let { Path.of(it).toAbsolutePath().normalize() }
                ?: home.resolve(".local/bin"),
        )
    }

    fun smokeOptions(): SmokeOptions {
        if (options.containsKey("dir")) {
            throw CliFailure(
                code = "CLI_USAGE",
                message = "Use --workspace-root for `kast smoke`; --dir is not supported",
            )
        }
        if (options.containsKey("kast")) {
            throw CliFailure(
                code = "CLI_USAGE",
                message = "`kast smoke` does not accept --kast; invoke smoke.sh directly if you need to override the launcher",
            )
        }
        val format = options["format"]
            ?.takeIf(String::isNotBlank)
            ?.let(SmokeOutputFormat::fromCliValue)
            ?: SmokeOutputFormat.JSON
        if (options["format"] != null && SmokeOutputFormat.fromCliValue(checkNotNull(options["format"])) == null) {
            throw CliFailure(
                code = "CLI_USAGE",
                message = "Invalid value for --format; expected json or markdown",
            )
        }
        return SmokeOptions(
            workspaceRoot = options["workspace-root"]
                ?.takeIf(String::isNotBlank)
                ?.let { Path.of(it).toAbsolutePath().normalize() }
                ?: Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize(),
            fileFilter = options["file"]?.takeIf(String::isNotBlank),
            sourceSetFilter = options["source-set"]?.takeIf(String::isNotBlank),
            symbolFilter = options["symbol"]?.takeIf(String::isNotBlank),
            format = format,
        )
    }

    fun withoutOption(key: String): ParsedArguments = copy(options = options - key)

    fun daemonStartOptions(): DaemonStartOptions {
        val runtimeLibsDir = options["runtime-libs-dir"]
            ?.takeIf(String::isNotBlank)
            ?.let { Path.of(it).toAbsolutePath().normalize() }
        val forwardedArgs = (options - "runtime-libs-dir").map { (key, value) -> "--$key=$value" }
        return DaemonStartOptions(
            standaloneArgs = forwardedArgs,
            workspaceRoot = options["workspace-root"]
                ?.takeIf(String::isNotBlank)
                ?.let { Path.of(it).toAbsolutePath().normalize() }
                ?: Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize(),
            runtimeLibsDir = runtimeLibsDir,
        )
    }

    fun evalSkillOptions(): EvalSkillOptions {
        val skillDir = options["skill-dir"]
            ?.takeIf(String::isNotBlank)
            ?.let { Path.of(it).toAbsolutePath().normalize() }
            ?: Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize()
                .resolve(".agents/skills/kast")
        val compareBaseline = options["compare"]
            ?.takeIf(String::isNotBlank)
            ?.let { Path.of(it).toAbsolutePath().normalize() }
        val format = when (options["format"]?.lowercase()) {
            "markdown" -> EvalOutputFormat.MARKDOWN
            "json", null -> EvalOutputFormat.JSON
            else -> throw CliFailure(
                code = "CLI_USAGE",
                message = "Unknown format: ${options["format"]}. Valid values: json, markdown",
            )
        }
        return EvalSkillOptions(
            skillDir = skillDir,
            compareBaseline = compareBaseline,
            format = format,
        )
    }

    private fun <T> requestOrFile(
        serializer: KSerializer<T>,
        requestFileKey: String,
        json: Json,
        fallback: () -> T,
    ): T {
        val requestFile = options[requestFileKey] ?: return fallback()
        val requestPath = Path.of(requestFile).toAbsolutePath().normalize()
        return json.decodeFromString(serializer, requestPath.readText())
    }

    private fun requireOption(key: String): String = options[key]
        ?: throw CliFailure(
            code = "CLI_USAGE",
            message = "Missing required option --$key",
        )

    private fun requireInt(key: String): Int = options[key]?.toIntOrNull()
        ?: throw CliFailure(
            code = "CLI_USAGE",
            message = "Missing required integer option --$key",
        )

    private fun optionalInt(
        key: String,
        default: Int,
    ): Int = options[key]?.toIntOrNull() ?: default

    private fun requireCallDirection(key: String): CallDirection = when (requireOption(key).lowercase()) {
        "incoming" -> CallDirection.INCOMING
        "outgoing" -> CallDirection.OUTGOING
        else -> throw CliFailure(
            code = "CLI_USAGE",
            message = "Invalid value for --$key; expected incoming or outgoing",
        )
    }

    private fun requireTypeHierarchyDirection(key: String): TypeHierarchyDirection = when (requireOption(key).lowercase()) {
        "supertypes" -> TypeHierarchyDirection.SUPERTYPES
        "subtypes" -> TypeHierarchyDirection.SUBTYPES
        "both" -> TypeHierarchyDirection.BOTH
        else -> throw CliFailure(
            code = "CLI_USAGE",
            message = "Invalid value for --$key; expected supertypes, subtypes, or both",
        )
    }

    private fun requireSemanticInsertionTarget(key: String): SemanticInsertionTarget = when (requireOption(key).lowercase()) {
        "class-body-start" -> SemanticInsertionTarget.CLASS_BODY_START
        "class-body-end" -> SemanticInsertionTarget.CLASS_BODY_END
        "file-top" -> SemanticInsertionTarget.FILE_TOP
        "file-bottom" -> SemanticInsertionTarget.FILE_BOTTOM
        "after-imports" -> SemanticInsertionTarget.AFTER_IMPORTS
        else -> throw CliFailure(
            code = "CLI_USAGE",
            message = "Invalid value for --$key; expected class-body-start, class-body-end, file-top, file-bottom, or after-imports",
        )
    }

    private fun optionalBoolean(
        key: String,
        default: Boolean,
    ): Boolean = options[key]?.toBooleanStrictOrNull() ?: default

    private fun absoluteFilePath(value: String): String = Path.of(value).toAbsolutePath().normalize().toString()

    fun requireWorkspaceRootPath(): Path {
        val raw = options["workspace-root"]
            ?: throw CliFailure(
                code = "CLI_USAGE",
                message = "Missing required option --workspace-root",
            )
        return Path.of(raw).toAbsolutePath().normalize()
    }

    fun optionalInt(key: String): Int? = options[key]?.toIntOrNull()
}

private fun ParsedArguments.parseBool(key: String): Boolean = when (options[key]?.lowercase()) {
    null, "", "true", "on", "yes", "1" -> options.containsKey(key)
    "false", "off", "no", "0" -> false
    else -> throw CliFailure(
        code = "CLI_USAGE",
        message = "Unknown value for --$key: ${options[key]}. Valid values: true, false.",
    )
}

private val VALID_BACKEND_NAMES = setOf("standalone", "intellij")
