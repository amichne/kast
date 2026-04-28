package io.github.amichne.kast.cli

internal const val CLI_EXECUTABLE_NAME = "kast"

internal enum class CliCommandGroup(
    val title: String,
    val overview: String,
) {
    WORKSPACE_LIFECYCLE(
        title = "Workspace lifecycle",
        overview = "Inspect, start, reuse, and stop the standalone daemon that serves one workspace.",
    ),
    ANALYSIS(
        title = "Analysis",
        overview = "Read-only commands for capabilities, symbols, references, hierarchy traversal, semantic insertion, diagnostics, outlines, workspace symbol search, implementations, code actions, and completions.",
    ),
    MUTATION_FLOW(
        title = "Mutation flow",
        overview = "Plan renames, optimize imports, or apply code edits through the daemon's mutation pipeline.",
    ),
    VALIDATION(
        title = "Validation",
        overview = "Exercise the public CLI surface against a real workspace before you trust a build, install, or agent setup.",
    ),
    SHELL_INTEGRATION(
        title = "Shell integration",
        overview = "Opt-in helpers for interactive terminals that keep the public command tree easy to drive.",
    ),
    CLI_MANAGEMENT(
        title = "CLI management",
        overview = "Install and manage local Kast CLI instances.",
    ),
    METRICS(
        title = "Metrics",
        overview = "Read-only workspace metrics computed directly from the local SQLite reference index — no running daemon required.",
    ),
}

internal enum class CliOptionCompletionKind {
    NONE,
    DIRECTORY,
    FILE,
    FILE_LIST,
    BOOLEAN,
}

internal data class CliOptionMetadata(
    val key: String,
    val usage: String,
    val description: String,
    val completionKind: CliOptionCompletionKind = CliOptionCompletionKind.NONE,
)

internal data class CliBuiltinMetadata(
    val usage: String,
    val summary: String,
)

internal data class CliCommandMetadata(
    val path: List<String>,
    val group: CliCommandGroup,
    val summary: String,
    val description: String,
    val usages: List<String>,
    val options: List<CliOptionMetadata> = emptyList(),
    val examples: List<String> = emptyList(),
    val visible: Boolean = true,
) {
    val commandText: String = path.joinToString(" ")
}

internal object CliCommandCatalog {
    private val builtins: List<CliBuiltinMetadata> = listOf(
        CliBuiltinMetadata(
            usage = "help [topic...]",
            summary = "Browse the command tree and scoped help pages.",
        ),
        CliBuiltinMetadata(
            usage = "version",
            summary = "Print the packaged CLI version.",
        ),
        CliBuiltinMetadata(
            usage = "--help",
            summary = "Open the same top-level help page from any command position.",
        ),
        CliBuiltinMetadata(
            usage = "--version",
            summary = "Print the packaged CLI version as a flag.",
        ),
    )
    private val backendNameOption = CliOptionMetadata(
        key = "backend-name",
        usage = "--backend-name=intellij|standalone",
        description = "Pin the command to a specific backend. " +
            "When omitted, intellij is preferred if running for that workspace; standalone is used if already running. " +
            "If no backend is running, the command fails with NO_BACKEND_AVAILABLE. " +
            "Start a backend first with `kast daemon start --workspace-root=<path>` or open the project in IntelliJ with the Kast plugin installed. " +
            "Set KAST_INTELLIJ_DISABLE=1 to prevent the plugin from starting inside IntelliJ IDEA.",
    )
    private val workspaceRootOption = CliOptionMetadata(
        key = "workspace-root",
        usage = "--workspace-root=/absolute/path/to/workspace",
        description = "Absolute workspace root to inspect, ensure, or analyze.",
        completionKind = CliOptionCompletionKind.DIRECTORY,
    )
    private val waitTimeoutOption = CliOptionMetadata(
        key = "wait-timeout-ms",
        usage = "--wait-timeout-ms=60000",
        description = "Maximum time to wait for a ready daemon when a command needs one.",
    )
    private val acceptIndexingOption = CliOptionMetadata(
        key = "accept-indexing",
        usage = "--accept-indexing=true",
        description = "Allow workspace ensure to return once the daemon is servable in INDEXING. Defaults to false.",
        completionKind = CliOptionCompletionKind.BOOLEAN,
    )
    private val noAutoStartOption = CliOptionMetadata(
        key = "no-auto-start",
        usage = "--no-auto-start=true",
        description = "Fail instead of auto-starting a standalone daemon when none is servable. Defaults to false.",
        completionKind = CliOptionCompletionKind.BOOLEAN,
    )
    private val requestFileOption = CliOptionMetadata(
        key = "request-file",
        usage = "--request-file=/absolute/path/to/query.json",
        description = "Absolute JSON request file for operations with richer payloads.",
        completionKind = CliOptionCompletionKind.FILE,
    )
    private val filePathOption = CliOptionMetadata(
        key = "file-path",
        usage = "--file-path=/absolute/path/to/File.kt",
        description = "Absolute file path for position-based requests.",
        completionKind = CliOptionCompletionKind.FILE,
    )
    private val offsetOption = CliOptionMetadata(
        key = "offset",
        usage = "--offset=123",
        description = "Character offset inside the file identified by --file-path.",
    )
    private val filePathsOption = CliOptionMetadata(
        key = "file-paths",
        usage = "--file-paths=/absolute/A.kt,/absolute/B.kt",
        description = "Comma-separated absolute file paths for file-list requests.",
        completionKind = CliOptionCompletionKind.FILE_LIST,
    )
    private val insertionTargetOption = CliOptionMetadata(
        key = "target",
        usage = "--target=after-imports",
        description = "Semantic insertion target. Use class-body-start, class-body-end, file-top, file-bottom, or after-imports.",
    )
    private val includeDeclarationOption = CliOptionMetadata(
        key = "include-declaration",
        usage = "--include-declaration=true",
        description = "Include the declaration alongside reference results. Defaults to false.",
        completionKind = CliOptionCompletionKind.BOOLEAN,
    )
    private val includeBodyOption = CliOptionMetadata(
        key = "include-body",
        usage = "--include-body=true",
        description = "Include the full declaration scope (text range and source text) on each resolved symbol. Defaults to false.",
        completionKind = CliOptionCompletionKind.BOOLEAN,
    )
    private val includeDocumentationOption = CliOptionMetadata(
        key = "include-documentation",
        usage = "--include-documentation=true",
        description = "Include declaration documentation and parameter metadata when supported. Defaults to false.",
        completionKind = CliOptionCompletionKind.BOOLEAN,
    )
    private val directionOption = CliOptionMetadata(
        key = "direction",
        usage = "--direction=incoming",
        description = "Traversal direction for call hierarchy. Use incoming or outgoing.",
    )
    private val typeHierarchyDirectionOption = CliOptionMetadata(
        key = "direction",
        usage = "--direction=both",
        description = "Traversal direction for type hierarchy. Use supertypes, subtypes, or both.",
    )
    private val depthOption = CliOptionMetadata(
        key = "depth",
        usage = "--depth=3",
        description = "Maximum edge depth to expand from the selected declaration. Defaults to 3.",
    )
    private val maxResultsOption = CliOptionMetadata(
        key = "max-results",
        usage = "--max-results=256",
        description = "Maximum total hierarchy nodes to include before truncation. Defaults to 256.",
    )
    private val maxTotalCallsOption = CliOptionMetadata(
        key = "max-total-calls",
        usage = "--max-total-calls=256",
        description = "Maximum total call edges to include before truncation. Defaults to 256.",
    )
    private val maxChildrenPerNodeOption = CliOptionMetadata(
        key = "max-children-per-node",
        usage = "--max-children-per-node=64",
        description = "Maximum children to include for any single node before truncation. Defaults to 64.",
    )
    private val timeoutMillisOption = CliOptionMetadata(
        key = "timeout-millis",
        usage = "--timeout-millis=5000",
        description = "Optional traversal timeout in milliseconds. When omitted, the daemon limit applies.",
    )
    private val newNameOption = CliOptionMetadata(
        key = "new-name",
        usage = "--new-name=RenamedSymbol",
        description = "Replacement symbol name for rename planning.",
    )
    private val moduleNameOption = CliOptionMetadata(
        key = "module-name",
        usage = "--module-name=app",
        description = "Filter workspace file listing to a single module. Omit for all modules.",
    )
    private val includeFilesOption = CliOptionMetadata(
        key = "include-files",
        usage = "--include-files=true",
        description = "Enumerate individual file paths per module. Defaults to false.",
        completionKind = CliOptionCompletionKind.BOOLEAN,
    )
    private val dryRunOption = CliOptionMetadata(
        key = "dry-run",
        usage = "--dry-run=true",
        description = "Keep rename in planning mode. Defaults to true.",
        completionKind = CliOptionCompletionKind.BOOLEAN,
    )
    private val patternOption = CliOptionMetadata(
        key = "pattern",
        usage = "--pattern=MyClass",
        description = "Symbol name search pattern. Case-insensitive substring match by default; regex when --regex=true.",
    )
    private val regexOption = CliOptionMetadata(
        key = "regex",
        usage = "--regex=true",
        description = "Treat the pattern as a regular expression. Defaults to false.",
        completionKind = CliOptionCompletionKind.BOOLEAN,
    )
    private val kindOption = CliOptionMetadata(
        key = "kind",
        usage = "--kind=CLASS",
        description = "Filter by symbol kind: CLASS, OBJECT, INTERFACE, ENUM, FUNCTION, PROPERTY, etc.",
    )
    private val kindFilterOption = CliOptionMetadata(
        key = "kind-filter",
        usage = "--kind-filter=FUNCTION,CLASS",
        description = "Optional comma-separated symbol kinds to include in completion results.",
    )
    private val diagnosticCodeOption = CliOptionMetadata(
        key = "diagnostic-code",
        usage = "--diagnostic-code=UNRESOLVED_REFERENCE",
        description = "Optional diagnostic code filter for code actions.",
    )
    private val archiveOption = CliOptionMetadata(
        key = "archive",
        usage = "--archive=/absolute/path/to/kast-portable.zip",
        description = "Absolute path to the portable Kast zip archive to install.",
        completionKind = CliOptionCompletionKind.FILE,
    )
    private val instanceNameOption = CliOptionMetadata(
        key = "instance",
        usage = "--instance=my-dev",
        description = "Instance name for the installed build. Defaults to a generated adjective-animal.",
    )
    private val instancesRootOption = CliOptionMetadata(
        key = "instances-root",
        usage = "--instances-root=/absolute/path/to/instances",
        description = "Root directory for instances. Defaults to ~/.local/share/kast/instances.",
        completionKind = CliOptionCompletionKind.DIRECTORY,
    )
    private val binDirOption = CliOptionMetadata(
        key = "bin-dir",
        usage = "--bin-dir=/absolute/path/to/bin",
        description = "Directory for launcher scripts. Defaults to ~/.local/bin.",
        completionKind = CliOptionCompletionKind.DIRECTORY,
    )
    private val skillTargetDirOption = CliOptionMetadata(
        key = "target-dir",
        usage = "--target-dir=/absolute/path/to/skills",
        description = "Directory to install the packaged skill in. Auto-detected from CWD when omitted.",
        completionKind = CliOptionCompletionKind.DIRECTORY,
    )
    private val skillNameOption = CliOptionMetadata(
        key = "name",
        usage = "--name=kast",
        description = "Directory name for the installed skill. Defaults to kast.",
    )
    private val skillLinkNameAliasOption = CliOptionMetadata(
        key = "link-name",
        usage = "--link-name=kast",
        description = "Deprecated alias for --name.",
    )
    private val yesOption = CliOptionMetadata(
        key = "yes",
        usage = "--yes=true",
        description = "Overwrite an existing installed skill directory without prompting. Defaults to false.",
        completionKind = CliOptionCompletionKind.BOOLEAN,
    )
    private val smokeFileOption = CliOptionMetadata(
        key = "file",
        usage = "--file=CliCommandCatalog.kt",
        description = "Only keep discovered declarations whose basename or workspace-relative path matches this text.",
        completionKind = CliOptionCompletionKind.FILE,
    )
    private val smokeSourceSetOption = CliOptionMetadata(
        key = "source-set",
        usage = "--source-set=:kast-cli:test",
        description = "Only keep discovered declarations from matching `:module:sourceSet` keys.",
    )
    private val smokeSymbolOption = CliOptionMetadata(
        key = "symbol",
        usage = "--symbol=KastCli",
        description = "Only keep discovered declarations whose symbol name matches this text.",
    )
    private val smokeFormatOption = CliOptionMetadata(
        key = "format",
        usage = "--format=json",
        description = "Render the smoke report as json or markdown. Defaults to json.",
    )
    private val daemonRuntimeLibsDirOption = CliOptionMetadata(
        key = "runtime-libs-dir",
        usage = "--runtime-libs-dir=/absolute/path/to/runtime-libs",
        description = "Override the directory containing the backend runtime classpath. " +
            "Defaults to KAST_STANDALONE_RUNTIME_LIBS env var, then \$KAST_INSTALL_ROOT/backends/current/runtime-libs.",
        completionKind = CliOptionCompletionKind.DIRECTORY,
    )
    private val metricsLimitOption = CliOptionMetadata(
        key = "limit",
        usage = "--limit=50",
        description = "Maximum number of result rows. Defaults to 50.",
    )
    private val metricsSymbolOption = CliOptionMetadata(
        key = "symbol",
        usage = "--symbol=com.example.MyClass",
        description = "Fully qualified symbol name for impact analysis.",
    )
    private val metricsDepthOption = CliOptionMetadata(
        key = "depth",
        usage = "--depth=3",
        description = "Maximum edge depth for impact traversal. Defaults to 3.",
    )

    private val commands: List<CliCommandMetadata> = listOf(
        CliCommandMetadata(
            path = listOf("workspace", "status"),
            group = CliCommandGroup.WORKSPACE_LIFECYCLE,
            summary = "Inspect daemon descriptors, liveness, and readiness for a workspace.",
            description = "Reports the selected daemon plus any additional descriptors registered for the same workspace.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME workspace status --workspace-root=/absolute/path/to/workspace",
            ),
            options = listOf(workspaceRootOption, backendNameOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME workspace status --workspace-root=/absolute/path/to/workspace",
                "$CLI_EXECUTABLE_NAME workspace status --workspace-root=/absolute/path/to/workspace --backend-name=intellij",
            ),
        ),
        CliCommandMetadata(
            path = listOf("workspace", "ensure"),
            group = CliCommandGroup.WORKSPACE_LIFECYCLE,
            summary = "Ensure a healthy backend is running for the workspace.",
        description = "Ensures a healthy backend exists for the workspace. " +
                "When the IntelliJ plugin is running it is used automatically; otherwise the standalone backend is used if already running. " +
                "Use --backend-name=standalone or --backend-name=intellij to pin to a specific backend. " +
                "If no backend is running, the command fails — start one first with `kast daemon start --workspace-root=<path>` or open IntelliJ with the plugin installed. " +
                "Use this command to pre-warm the runtime or check readiness ahead of analysis commands.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME workspace ensure --workspace-root=/absolute/path/to/workspace [--backend-name=intellij|standalone] [--wait-timeout-ms=60000] [--accept-indexing=true]",
            ),
            options = listOf(workspaceRootOption, backendNameOption, waitTimeoutOption, acceptIndexingOption, noAutoStartOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME workspace ensure --workspace-root=/absolute/path/to/workspace",
                "$CLI_EXECUTABLE_NAME workspace ensure --workspace-root=/absolute/path/to/workspace --backend-name=standalone",
                "$CLI_EXECUTABLE_NAME workspace ensure --workspace-root=/absolute/path/to/workspace --backend-name=intellij",
            ),
        ),
        CliCommandMetadata(
            path = listOf("workspace", "refresh"),
            group = CliCommandGroup.WORKSPACE_LIFECYCLE,
            summary = "Force the daemon to refresh workspace files and indexes.",
            description = "Triggers a targeted refresh for the given Kotlin file paths, or a full workspace refresh when no file paths are provided.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME workspace refresh --workspace-root=/absolute/path/to/workspace [--file-paths=/absolute/A.kt,/absolute/B.kt]",
                "$CLI_EXECUTABLE_NAME workspace refresh --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json",
            ),
            options = listOf(workspaceRootOption, backendNameOption, waitTimeoutOption, noAutoStartOption, requestFileOption, filePathsOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME workspace refresh --workspace-root=/absolute/path/to/workspace",
                "$CLI_EXECUTABLE_NAME workspace refresh --workspace-root=/absolute/path/to/workspace --file-paths=/absolute/path/to/File.kt",
            ),
        ),
        CliCommandMetadata(
            path = listOf("workspace", "stop"),
            group = CliCommandGroup.WORKSPACE_LIFECYCLE,
            summary = "Stop a running backend for the workspace.",
            description = "Stops the selected backend, removes its descriptor, and reports what was stopped. " +
                "Use --backend-name to target a specific backend; otherwise stops the first candidate found.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME workspace stop --workspace-root=/absolute/path/to/workspace [--backend-name=standalone|intellij]",
            ),
            options = listOf(workspaceRootOption, backendNameOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME workspace stop --workspace-root=/absolute/path/to/workspace",
                "$CLI_EXECUTABLE_NAME workspace stop --workspace-root=/absolute/path/to/workspace --backend-name=standalone",
            ),
        ),
        CliCommandMetadata(
            path = listOf("workspace", "files"),
            group = CliCommandGroup.WORKSPACE_LIFECYCLE,
            summary = "List workspace modules and their Kotlin source files.",
            description = "Returns the module layout discovered by the backend, including source roots and " +
                "dependency relationships. Pass --include-files to enumerate individual .kt file paths per module.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME workspace files --workspace-root=/absolute/path/to/workspace",
                "$CLI_EXECUTABLE_NAME workspace files --workspace-root=/absolute/path/to/workspace --include-files=true [--module-name=app]",
            ),
            options = listOf(workspaceRootOption, backendNameOption, waitTimeoutOption, noAutoStartOption, requestFileOption, moduleNameOption, includeFilesOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME workspace files --workspace-root=/absolute/path/to/workspace",
                "$CLI_EXECUTABLE_NAME workspace files --workspace-root=/absolute/path/to/workspace --include-files=true",
                "$CLI_EXECUTABLE_NAME workspace files --workspace-root=/absolute/path/to/workspace --module-name=app --include-files=true",
            ),
        ),
        CliCommandMetadata(
            path = listOf("daemon", "start"),
            group = CliCommandGroup.WORKSPACE_LIFECYCLE,
            summary = "Start the standalone JVM backend for a workspace.",
            description = "Launches the standalone JVM backend process for the given workspace. " +
                "The process runs in the foreground; use a terminal multiplexer or background shell job to keep it alive. " +
                "The backend runtime-libs are located from the KAST_STANDALONE_RUNTIME_LIBS environment variable or " +
                "from \$KAST_INSTALL_ROOT/backends/current/runtime-libs. " +
                "Pass --runtime-libs-dir to override both. " +
                "All other options are forwarded verbatim to the backend process. " +
                "Once running, send analysis commands with `$CLI_EXECUTABLE_NAME workspace ensure --workspace-root=<path>` to verify it is ready.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME daemon start --workspace-root=/absolute/path/to/workspace [--socket-path=...] [--runtime-libs-dir=...]",
            ),
            options = listOf(
                workspaceRootOption,
                daemonRuntimeLibsDirOption,
                CliOptionMetadata(
                    key = "socket-path",
                    usage = "--socket-path=/absolute/path/to/socket",
                    description = "Unix-domain socket path for the backend to listen on. Auto-computed from workspace-root when omitted.",
                ),
                CliOptionMetadata(
                    key = "module-name",
                    usage = "--module-name=app",
                    description = "Source module name (passed to the backend). Defaults to 'sources'.",
                ),
                CliOptionMetadata(
                    key = "source-roots",
                    usage = "--source-roots=/abs/src/main/kotlin,/abs/src/test/kotlin",
                    description = "Comma-separated source root paths to index (passed to the backend).",
                ),
                CliOptionMetadata(
                    key = "classpath",
                    usage = "--classpath=/abs/lib/a.jar,/abs/lib/b.jar",
                    description = "Comma-separated classpath JAR paths (passed to the backend).",
                ),
                CliOptionMetadata(
                    key = "request-timeout-ms",
                    usage = "--request-timeout-ms=30000",
                    description = "Request timeout in milliseconds (passed to the backend). Defaults to 30000.",
                ),
                CliOptionMetadata(
                    key = "max-results",
                    usage = "--max-results=500",
                    description = "Maximum results the backend returns per request. Defaults to 500.",
                ),
            ),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME daemon start --workspace-root=/absolute/path/to/workspace",
                "$CLI_EXECUTABLE_NAME daemon start --workspace-root=/absolute/path/to/workspace --module-name=myApp",
                "$CLI_EXECUTABLE_NAME daemon start --workspace-root=/absolute/path/to/workspace --runtime-libs-dir=/path/to/runtime-libs",
            ),
        ),
        CliCommandMetadata(
            path = listOf("capabilities"),
            group = CliCommandGroup.ANALYSIS,
            summary = "Print the advertised capabilities for the workspace backend.",
            description = "Ensures the workspace has a servable backend, then returns its current capability set as JSON. " +
                "Use --backend-name to pin to a specific backend.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME capabilities --workspace-root=/absolute/path/to/workspace [--backend-name=intellij|standalone] [--wait-timeout-ms=60000]",
            ),
            options = listOf(workspaceRootOption, backendNameOption, waitTimeoutOption, noAutoStartOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME capabilities --workspace-root=/absolute/path/to/workspace",
                "$CLI_EXECUTABLE_NAME capabilities --workspace-root=/absolute/path/to/workspace --backend-name=intellij",
            ),
        ),
        CliCommandMetadata(
            path = listOf("resolve"),
            group = CliCommandGroup.ANALYSIS,
            summary = "Resolve the symbol at a file position.",
            description = "Accepts either an absolute request file or inline file position arguments.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME resolve --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json",
                "$CLI_EXECUTABLE_NAME resolve --workspace-root=/absolute/path/to/workspace --file-path=/absolute/path/to/File.kt --offset=123",
            ),
            options = listOf(
                workspaceRootOption,
                backendNameOption,
                waitTimeoutOption,
                noAutoStartOption,
                requestFileOption,
                filePathOption,
                offsetOption,
                includeBodyOption,
                includeDocumentationOption,
            ),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME resolve --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json",
            ),
        ),
        CliCommandMetadata(
            path = listOf("references"),
            group = CliCommandGroup.ANALYSIS,
            summary = "Find references for the symbol at a file position.",
            description = "Accepts either an absolute request file or inline position arguments with an optional declaration toggle.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME references --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json",
                "$CLI_EXECUTABLE_NAME references --workspace-root=/absolute/path/to/workspace --file-path=/absolute/path/to/File.kt --offset=123 [--include-declaration=true]",
            ),
            options = listOf(
                workspaceRootOption,
                backendNameOption,
                waitTimeoutOption,
                noAutoStartOption,
                requestFileOption,
                filePathOption,
                offsetOption,
                includeDeclarationOption,
            ),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME references --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json",
            ),
        ),
        CliCommandMetadata(
            path = listOf("call-hierarchy"),
            group = CliCommandGroup.ANALYSIS,
            summary = "Expand a bounded call hierarchy for the symbol at a file position.",
            description = "Accepts either an absolute request file or inline position, direction, and bound arguments.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME call-hierarchy --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json",
                "$CLI_EXECUTABLE_NAME call-hierarchy --workspace-root=/absolute/path/to/workspace --file-path=/absolute/path/to/File.kt --offset=123 --direction=incoming [--depth=3] [--max-total-calls=256] [--max-children-per-node=64] [--timeout-millis=5000]",
            ),
            options = listOf(
                workspaceRootOption,
                backendNameOption,
                waitTimeoutOption,
                noAutoStartOption,
                requestFileOption,
                filePathOption,
                offsetOption,
                directionOption,
                depthOption,
                maxTotalCallsOption,
                maxChildrenPerNodeOption,
                timeoutMillisOption,
            ),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME call-hierarchy --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json",
            ),
        ),
        CliCommandMetadata(
            path = listOf("type-hierarchy"),
            group = CliCommandGroup.ANALYSIS,
            summary = "Expand a bounded type hierarchy for the declaration at a file position.",
            description = "Accepts either an absolute request file or inline position, direction, and bound arguments.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME type-hierarchy --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json",
                "$CLI_EXECUTABLE_NAME type-hierarchy --workspace-root=/absolute/path/to/workspace --file-path=/absolute/path/to/File.kt --offset=123 --direction=both [--depth=3] [--max-results=256]",
            ),
            options = listOf(
                workspaceRootOption,
                backendNameOption,
                waitTimeoutOption,
                noAutoStartOption,
                requestFileOption,
                filePathOption,
                offsetOption,
                typeHierarchyDirectionOption,
                depthOption,
                maxResultsOption,
            ),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME type-hierarchy --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json",
            ),
        ),
        CliCommandMetadata(
            path = listOf("insertion-point"),
            group = CliCommandGroup.ANALYSIS,
            summary = "Resolve a semantic insertion offset for the declaration or file at a position.",
            description = "Accepts either an absolute request file or inline file position plus semantic target arguments.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME insertion-point --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json",
                "$CLI_EXECUTABLE_NAME insertion-point --workspace-root=/absolute/path/to/workspace --file-path=/absolute/path/to/File.kt --offset=123 --target=after-imports",
            ),
            options = listOf(
                workspaceRootOption,
                backendNameOption,
                waitTimeoutOption,
                noAutoStartOption,
                requestFileOption,
                filePathOption,
                offsetOption,
                insertionTargetOption,
            ),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME insertion-point --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json",
            ),
        ),
        CliCommandMetadata(
            path = listOf("diagnostics"),
            group = CliCommandGroup.ANALYSIS,
            summary = "Run diagnostics for one or more files.",
            description = "Accepts either an absolute request file or inline comma-separated file paths.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME diagnostics --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json",
                "$CLI_EXECUTABLE_NAME diagnostics --workspace-root=/absolute/path/to/workspace --file-paths=/absolute/A.kt,/absolute/B.kt",
            ),
            options = listOf(workspaceRootOption, backendNameOption, waitTimeoutOption, noAutoStartOption, requestFileOption, filePathsOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME diagnostics --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json",
            ),
        ),
        CliCommandMetadata(
            path = listOf("outline"),
            group = CliCommandGroup.ANALYSIS,
            summary = "Produce a hierarchical file outline of declarations.",
            description = "Lists all classes, functions, and properties in a file as a nested outline tree.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME outline --workspace-root=/absolute/path/to/workspace --file-path=/absolute/path/to/File.kt",
            ),
            options = listOf(workspaceRootOption, backendNameOption, waitTimeoutOption, noAutoStartOption, filePathOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME outline --workspace-root=/absolute/path/to/workspace --file-path=/absolute/path/to/File.kt",
            ),
        ),
        CliCommandMetadata(
            path = listOf("workspace-symbol"),
            group = CliCommandGroup.ANALYSIS,
            summary = "Search for symbols across the workspace by name or regex.",
            description = "Returns matching classes, functions, and properties. Substring search is the default; pass --regex=true for regular expression matching.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME workspace-symbol --workspace-root=/absolute/path/to/workspace --pattern=MyClass",
                "$CLI_EXECUTABLE_NAME workspace-symbol --workspace-root=/absolute/path/to/workspace --pattern=.*Service --regex=true --kind=CLASS",
            ),
            options = listOf(workspaceRootOption, backendNameOption, waitTimeoutOption, noAutoStartOption, patternOption, regexOption, kindOption, maxResultsOption, includeBodyOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME workspace-symbol --workspace-root=/absolute/path/to/workspace --pattern=MyClass",
            ),
        ),
        CliCommandMetadata(
            path = listOf("implementations"),
            group = CliCommandGroup.ANALYSIS,
            summary = "Find concrete implementations/subclasses for the declaration at a file position.",
            description = "Accepts either an absolute request file or inline position with optional max-results bounds.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME implementations --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json",
                "$CLI_EXECUTABLE_NAME implementations --workspace-root=/absolute/path/to/workspace --file-path=/absolute/path/to/File.kt --offset=123 [--max-results=100]",
            ),
            options = listOf(
                workspaceRootOption,
                backendNameOption,
                waitTimeoutOption,
                noAutoStartOption,
                requestFileOption,
                filePathOption,
                offsetOption,
                maxResultsOption,
            ),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME implementations --workspace-root=/absolute/path/to/workspace --file-path=/absolute/path/to/File.kt --offset=123",
            ),
        ),
        CliCommandMetadata(
            path = listOf("code-actions"),
            group = CliCommandGroup.ANALYSIS,
            summary = "Return structured code actions at a file position.",
            description = "Accepts either an absolute request file or inline position with an optional diagnostic-code filter.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME code-actions --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json",
                "$CLI_EXECUTABLE_NAME code-actions --workspace-root=/absolute/path/to/workspace --file-path=/absolute/path/to/File.kt --offset=123 [--diagnostic-code=UNRESOLVED_REFERENCE]",
            ),
            options = listOf(
                workspaceRootOption,
                backendNameOption,
                waitTimeoutOption,
                noAutoStartOption,
                requestFileOption,
                filePathOption,
                offsetOption,
                diagnosticCodeOption,
            ),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME code-actions --workspace-root=/absolute/path/to/workspace --file-path=/absolute/path/to/File.kt --offset=123",
            ),
        ),
        CliCommandMetadata(
            path = listOf("completions"),
            group = CliCommandGroup.ANALYSIS,
            summary = "List completion candidates available at a file position.",
            description = "Accepts either an absolute request file or inline position with optional kind and max-results filters.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME completions --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json",
                "$CLI_EXECUTABLE_NAME completions --workspace-root=/absolute/path/to/workspace --file-path=/absolute/path/to/File.kt --offset=123 [--max-results=100] [--kind-filter=FUNCTION,CLASS]",
            ),
            options = listOf(
                workspaceRootOption,
                backendNameOption,
                waitTimeoutOption,
                noAutoStartOption,
                requestFileOption,
                filePathOption,
                offsetOption,
                maxResultsOption,
                kindFilterOption,
            ),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME completions --workspace-root=/absolute/path/to/workspace --file-path=/absolute/path/to/File.kt --offset=123",
            ),
        ),
        CliCommandMetadata(
            path = listOf("rename"),
            group = CliCommandGroup.MUTATION_FLOW,
            summary = "Plan a rename operation.",
            description = "Accepts either an absolute request file or inline position plus new-name arguments.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME rename --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json",
                "$CLI_EXECUTABLE_NAME rename --workspace-root=/absolute/path/to/workspace --file-path=/absolute/path/to/File.kt --offset=123 --new-name=RenamedSymbol [--dry-run=true]",
            ),
            options = listOf(
                workspaceRootOption,
                backendNameOption,
                waitTimeoutOption,
                noAutoStartOption,
                requestFileOption,
                filePathOption,
                offsetOption,
                newNameOption,
                dryRunOption,
            ),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME rename --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json",
            ),
        ),
        CliCommandMetadata(
            path = listOf("optimize-imports"),
            group = CliCommandGroup.MUTATION_FLOW,
            summary = "Prepare import cleanup edits for one or more files.",
            description = "Accepts either an absolute request file or inline comma-separated file paths and returns the edit plan needed to remove unused imports.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME optimize-imports --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json",
                "$CLI_EXECUTABLE_NAME optimize-imports --workspace-root=/absolute/path/to/workspace --file-paths=/absolute/A.kt,/absolute/B.kt",
            ),
            options = listOf(workspaceRootOption, backendNameOption, waitTimeoutOption, noAutoStartOption, requestFileOption, filePathsOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME optimize-imports --workspace-root=/absolute/path/to/workspace --file-paths=/absolute/path/to/File.kt",
            ),
        ),
        CliCommandMetadata(
            path = listOf("apply-edits"),
            group = CliCommandGroup.MUTATION_FLOW,
            summary = "Apply a prepared edit plan.",
            description = "Requires an absolute request file that contains the edits and expected file hashes.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME apply-edits --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json",
            ),
            options = listOf(workspaceRootOption, backendNameOption, waitTimeoutOption, noAutoStartOption, requestFileOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME apply-edits --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json",
            ),
        ),
        CliCommandMetadata(
            path = listOf("completion", "bash"),
            group = CliCommandGroup.SHELL_INTEGRATION,
            summary = "Emit an opt-in Bash completion script.",
            description = "Prints a Bash completion definition for the public command tree and the supported --key=value options.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME completion bash",
            ),
            examples = listOf(
                "source <($CLI_EXECUTABLE_NAME completion bash)",
            ),
        ),
        CliCommandMetadata(
            path = listOf("completion", "zsh"),
            group = CliCommandGroup.SHELL_INTEGRATION,
            summary = "Emit an opt-in Zsh completion script.",
            description = "Prints a Zsh completion definition that bootstraps bash completion emulation and understands the public command tree.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME completion zsh",
            ),
            examples = listOf(
                "source <($CLI_EXECUTABLE_NAME completion zsh)",
            ),
        ),
        CliCommandMetadata(
            path = listOf("install"),
            group = CliCommandGroup.CLI_MANAGEMENT,
            summary = "Install a portable Kast archive as a named local instance.",
            description = "Extracts a portable zip archive, wires up the instance under the instances root, and creates a launcher script in the bin directory.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME install --archive=/absolute/path/to/kast-portable.zip [--instance=<name>] [--bin-dir=~/.local/bin] [--instances-root=~/.local/share/kast/instances]",
            ),
            options = listOf(archiveOption, instanceNameOption, binDirOption, instancesRootOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME install --archive=/path/to/kast-portable.zip",
                "$CLI_EXECUTABLE_NAME install --archive=/path/to/kast-portable.zip --instance=my-dev",
            ),
        ),
        CliCommandMetadata(
            path = listOf("install", "skill"),
            group = CliCommandGroup.CLI_MANAGEMENT,
            summary = "Install the packaged kast skill into the current workspace.",
            description = "Copies the bundled kast skill into the nearest recognised skills directory (.agents/skills, .github/skills, or .claude/skills), or the path given by --target-dir. Installed skill trees include a .kast-version marker so matching installs can be skipped safely.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME install skill [--target-dir=/absolute/path/to/skills] [--name=kast] [--yes=true]",
            ),
            options = listOf(skillTargetDirOption, skillNameOption, skillLinkNameAliasOption, yesOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME install skill",
                "$CLI_EXECUTABLE_NAME install skill --target-dir=/my/project/.agents/skills",
                "$CLI_EXECUTABLE_NAME install skill --name=kast-ci",
                "$CLI_EXECUTABLE_NAME install skill --yes=true",
            ),
        ),
        CliCommandMetadata(
            path = listOf("smoke"),
            group = CliCommandGroup.VALIDATION,
            summary = "Run the portable smoke workflow and emit an aggregated readiness report.",
            description = "Launches the maintained shell smoke script with the current kast executable. The report defaults to JSON for LLM-friendly consumption and can render markdown when you opt into --format=markdown.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME smoke [--workspace-root=/absolute/path/to/workspace] [--file=CliCommandCatalog.kt] [--source-set=:kast-cli:test] [--symbol=KastCli] [--format=json]",
            ),
            options = listOf(workspaceRootOption, smokeFileOption, smokeSourceSetOption, smokeSymbolOption, smokeFormatOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME smoke",
                "$CLI_EXECUTABLE_NAME smoke --workspace-root=/absolute/path/to/workspace --file=CliCommandCatalog.kt",
                "$CLI_EXECUTABLE_NAME smoke --workspace-root=/absolute/path/to/workspace --format=markdown",
            ),
        ),
        // Skill wrapper commands — hidden, called by agent shell scripts and SKILL.md tooling
        CliCommandMetadata(
            path = listOf("skill", "resolve"),
            group = CliCommandGroup.ANALYSIS,
            summary = "Skill wrapper: resolve a named symbol to a file position.",
            description = "Hidden native skill command. Accepts one JSON request argument.",
            usages = listOf("$CLI_EXECUTABLE_NAME skill resolve '{\"workspaceRoot\":\"/ws\",\"symbol\":\"MyClass\"}'"),
            visible = false,
        ),
        CliCommandMetadata(
            path = listOf("skill", "references"),
            group = CliCommandGroup.ANALYSIS,
            summary = "Skill wrapper: find references to a named symbol.",
            description = "Hidden native skill command. Accepts one JSON request argument.",
            usages = listOf("$CLI_EXECUTABLE_NAME skill references '{\"workspaceRoot\":\"/ws\",\"symbol\":\"myFun\"}'"),
            visible = false,
        ),
        CliCommandMetadata(
            path = listOf("skill", "callers"),
            group = CliCommandGroup.ANALYSIS,
            summary = "Skill wrapper: find callers of a named symbol.",
            description = "Hidden native skill command. Accepts one JSON request argument.",
            usages = listOf("$CLI_EXECUTABLE_NAME skill callers '{\"workspaceRoot\":\"/ws\",\"symbol\":\"myFun\"}'"),
            visible = false,
        ),
        CliCommandMetadata(
            path = listOf("skill", "diagnostics"),
            group = CliCommandGroup.ANALYSIS,
            summary = "Skill wrapper: run diagnostics on specified files.",
            description = "Hidden native skill command. Accepts one JSON request argument.",
            usages = listOf("$CLI_EXECUTABLE_NAME skill diagnostics '{\"workspaceRoot\":\"/ws\",\"filePaths\":[\"/ws/src/Main.kt\"]}'"),
            visible = false,
        ),
        CliCommandMetadata(
            path = listOf("skill", "rename"),
            group = CliCommandGroup.MUTATION_FLOW,
            summary = "Skill wrapper: rename a symbol with validation.",
            description = "Hidden native skill command. Accepts one JSON request argument.",
            usages = listOf("$CLI_EXECUTABLE_NAME skill rename '{\"workspaceRoot\":\"/ws\",\"type\":\"RENAME_BY_SYMBOL_REQUEST\",\"symbol\":\"old\",\"newName\":\"new\"}'"),
            visible = false,
        ),
        CliCommandMetadata(
            path = listOf("skill", "scaffold"),
            group = CliCommandGroup.ANALYSIS,
            summary = "Skill wrapper: scaffold context for a target file.",
            description = "Hidden native skill command. Accepts one JSON request argument.",
            usages = listOf("$CLI_EXECUTABLE_NAME skill scaffold '{\"workspaceRoot\":\"/ws\",\"targetFile\":\"/ws/src/Foo.kt\"}'"),
            visible = false,
        ),
        CliCommandMetadata(
            path = listOf("skill", "write-and-validate"),
            group = CliCommandGroup.MUTATION_FLOW,
            summary = "Skill wrapper: write code and validate with diagnostics.",
            description = "Hidden native skill command. Accepts one JSON request argument.",
            usages = listOf("$CLI_EXECUTABLE_NAME skill write-and-validate '{\"workspaceRoot\":\"/ws\",\"type\":\"CREATE_FILE_REQUEST\",\"filePath\":\"/ws/src/New.kt\"}'"),
            visible = false,
        ),
        CliCommandMetadata(
            path = listOf("skill", "workspace-files"),
            group = CliCommandGroup.ANALYSIS,
            summary = "Skill wrapper: list workspace modules and source files.",
            description = "Hidden native skill command. Accepts one JSON request argument.",
            usages = listOf("$CLI_EXECUTABLE_NAME skill workspace-files '{\"workspaceRoot\":\"/ws\"}'"),
            visible = false,
        ),
        CliCommandMetadata(
            path = listOf("metrics", "fan-in"),
            group = CliCommandGroup.METRICS,
            summary = "Show symbols ranked by incoming reference count (coupling hotspots).",
            description = "Queries the local SQLite reference index without a running daemon. Returns symbols with the highest inbound reference counts.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME metrics fan-in --workspace-root=/absolute/path/to/workspace [--limit=50]",
            ),
            options = listOf(workspaceRootOption, metricsLimitOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME metrics fan-in --workspace-root=/absolute/path/to/workspace",
                "$CLI_EXECUTABLE_NAME metrics fan-in --workspace-root=/absolute/path/to/workspace --limit=20",
            ),
        ),
        CliCommandMetadata(
            path = listOf("metrics", "fan-out"),
            group = CliCommandGroup.METRICS,
            summary = "Show files ranked by outgoing reference count (complexity hotspots).",
            description = "Queries the local SQLite reference index without a running daemon. Returns files with the highest outbound reference counts.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME metrics fan-out --workspace-root=/absolute/path/to/workspace [--limit=50]",
            ),
            options = listOf(workspaceRootOption, metricsLimitOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME metrics fan-out --workspace-root=/absolute/path/to/workspace",
                "$CLI_EXECUTABLE_NAME metrics fan-out --workspace-root=/absolute/path/to/workspace --limit=20",
            ),
        ),
        CliCommandMetadata(
            path = listOf("metrics", "coupling"),
            group = CliCommandGroup.METRICS,
            summary = "Show cross-module reference counts.",
            description = "Queries the local SQLite reference index without a running daemon. Returns module pairs with cross-module reference counts.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME metrics coupling --workspace-root=/absolute/path/to/workspace",
            ),
            options = listOf(workspaceRootOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME metrics coupling --workspace-root=/absolute/path/to/workspace",
            ),
        ),
        CliCommandMetadata(
            path = listOf("metrics", "dead-code"),
            group = CliCommandGroup.METRICS,
            summary = "Show symbols with zero incoming references.",
            description = "Queries the local SQLite reference index without a running daemon. Returns indexed identifiers that have no inbound symbol references.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME metrics dead-code --workspace-root=/absolute/path/to/workspace",
            ),
            options = listOf(workspaceRootOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME metrics dead-code --workspace-root=/absolute/path/to/workspace",
            ),
        ),
        CliCommandMetadata(
            path = listOf("metrics", "impact"),
            group = CliCommandGroup.METRICS,
            summary = "Show transitive change impact radius for a symbol.",
            description = "Queries the local SQLite reference index without a running daemon. Walks incoming reference edges up to --depth levels to estimate which files are transitively affected by a change to --symbol.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME metrics impact --workspace-root=/absolute/path/to/workspace --symbol=com.example.MyClass [--depth=3]",
            ),
            options = listOf(workspaceRootOption, metricsSymbolOption, metricsDepthOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME metrics impact --workspace-root=/absolute/path/to/workspace --symbol=com.example.MyClass",
                "$CLI_EXECUTABLE_NAME metrics impact --workspace-root=/absolute/path/to/workspace --symbol=com.example.MyClass --depth=5",
            ),
        ),
        // Skill wrapper: metrics — hidden, called by agent shell scripts
        CliCommandMetadata(
            path = listOf("skill", "metrics"),
            group = CliCommandGroup.METRICS,
            summary = "Skill wrapper: query workspace metrics from the local reference index.",
            description = "Hidden native skill command. Accepts one JSON request argument.",
            usages = listOf("$CLI_EXECUTABLE_NAME skill metrics '{\"workspaceRoot\":\"/ws\",\"metric\":\"fanIn\"}'"),
            visible = false,
        ),
        CliCommandMetadata(
            path = listOf("eval", "skill"),
            group = CliCommandGroup.VALIDATION,
            summary = "Evaluate the packaged kast skill for structural quality, budget, and contract compliance.",
            description = "Scans the skill directory, runs structural/contract/completeness checks, estimates token budgets, and produces a scored EvalResult. " +
                "Use --compare=baseline.json to compare against a baseline and exit non-zero on regression. " +
                "Use --format=markdown for a human-readable report.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME eval skill [--skill-dir=/path/to/.agents/skills/kast] [--compare=baseline.json] [--format=json|markdown]",
            ),
            options = listOf(
                CliOptionMetadata(
                    key = "skill-dir",
                    usage = "--skill-dir=/path/to/.agents/skills/kast",
                    description = "Path to the skill directory to evaluate. Defaults to .agents/skills/kast relative to workspace root.",
                ),
                CliOptionMetadata(
                    key = "compare",
                    usage = "--compare=baseline.json",
                    description = "Path to a baseline EvalResult JSON file. When provided, exits non-zero if score regresses.",
                ),
                CliOptionMetadata(
                    key = "format",
                    usage = "--format=json|markdown",
                    description = "Output format: json (default) or markdown.",
                ),
            ),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME eval skill",
                "$CLI_EXECUTABLE_NAME eval skill --compare=baseline.json",
                "$CLI_EXECUTABLE_NAME eval skill --format=markdown",
                "$CLI_EXECUTABLE_NAME eval skill --skill-dir=/path/to/.agents/skills/kast",
            ),
        ),
    )

    private val metadataByPath: Map<List<String>, CliCommandMetadata> = commands.associateBy(CliCommandMetadata::path)

    fun find(path: List<String>): CliCommandMetadata? = metadataByPath[path]

    fun visibleCommands(): List<CliCommandMetadata> = commands.filter(CliCommandMetadata::visible)

    fun topLevelCommandTopics(): List<String> = visibleCommands()
        .map { command -> command.path.first() }
        .distinct()

    fun commandsUnder(prefix: List<String>): List<CliCommandMetadata> = visibleCommands()
        .filter { prefix.isPrefixOf(it.path) }

    fun topLevelUsageDetails(): Map<String, String> = mapOf(
        "usage" to "$CLI_EXECUTABLE_NAME <command> [options]",
        "help" to "$CLI_EXECUTABLE_NAME help",
        "commands" to (listOf("help", "version") + visibleCommands().map(CliCommandMetadata::commandText)).joinToString(", "),
    )

    fun unknownCommandDetails(path: List<String>): Map<String, String> {
        val matchingSubcommands = commandsUnder(path)
            .mapNotNull { command -> command.path.getOrNull(path.size) }
            .distinct()

        return buildMap {
            putAll(topLevelUsageDetails())
            if (matchingSubcommands.isNotEmpty()) {
                put("subcommands", matchingSubcommands.joinToString(", "))
            }
        }
    }

    fun usageDetails(path: List<String>): Map<String, String> {
        val metadata = find(path) ?: return topLevelUsageDetails()
        return buildMap {
            put("usage", metadata.usages.joinToString("\n"))
            put("help", "$CLI_EXECUTABLE_NAME help ${metadata.commandText}")
            if (metadata.examples.isNotEmpty()) {
                put("examples", metadata.examples.joinToString("\n"))
            }
        }
    }

    fun versionText(
        version: String = currentCliVersion(),
        theme: CliTextTheme = CliTextTheme.detect(),
    ): String = theme.title("Kast CLI $version")

    fun helpText(
        topic: List<String>,
        version: String = currentCliVersion(),
        theme: CliTextTheme = CliTextTheme.detect(),
    ): String {
        if (topic.isEmpty()) {
            return topLevelHelp(version, theme)
        }

        val exact = find(topic)
        if (exact != null && exact.visible) {
            return commandHelp(exact, version, theme)
        }

        val namespaceCommands = commandsUnder(topic)
        if (namespaceCommands.isNotEmpty()) {
            return namespaceHelp(topic, namespaceCommands, version, theme)
        }

        return buildString {
            appendLine(theme.title("Kast CLI $version"))
            appendLine()
            appendLine("Unknown command topic: ${topic.joinToString(" ")}")
            appendLine(theme.muted("Use `$CLI_EXECUTABLE_NAME help` for the full command list."))
        }.trimEnd()
    }

    private fun topLevelHelp(
        version: String,
        theme: CliTextTheme,
    ): String = buildString {
        appendLine(theme.title("Kast CLI $version"))
        appendLine(theme.muted("Repo-local control plane for workspace daemons and Kotlin analysis requests."))
        appendLine()
        appendSection(
            title = "Usage",
            theme = theme,
        ) {
            appendLine(theme.command("  $CLI_EXECUTABLE_NAME <command> [options]"))
            appendLine(theme.command("  $CLI_EXECUTABLE_NAME help [topic...]"))
            appendLine(theme.command("  $CLI_EXECUTABLE_NAME --help"))
            appendLine(theme.command("  $CLI_EXECUTABLE_NAME --version"))
        }
        appendLine()
        appendSection(
            title = "Essentials",
            overview = "Start here for guided discovery, quick version checks, and built-in entrypoints.",
            theme = theme,
        ) {
            append(renderBuiltinTable(theme))
        }
        CliCommandGroup.entries.forEach { group ->
            val groupedCommands = visibleCommands().filter { command -> command.group == group }
            if (groupedCommands.isNotEmpty()) {
                appendLine()
                appendSection(
                    title = group.title,
                    overview = group.overview,
                    theme = theme,
                ) {
                    append(renderCommandTable(groupedCommands, theme))
                }
            }
        }
        appendLine()
        appendSection(
            title = "Notes",
            theme = theme,
        ) {
            appendLine("  JSON results stay on stdout.")
            appendLine("  `$CLI_EXECUTABLE_NAME smoke --format=markdown` opts into a human-readable report.")
            appendLine("  Daemon lifecycle notes, when present, stay on stderr after JSON-returning commands.")
            appendLine("  Every command option uses --key=value syntax.")
        }
        appendLine()
        appendSection(
            title = "Try",
            theme = theme,
        ) {
            appendLine(theme.command("  $CLI_EXECUTABLE_NAME workspace ensure --workspace-root=/absolute/path/to/workspace"))
            appendLine(theme.command("  $CLI_EXECUTABLE_NAME diagnostics --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json"))
            appendLine(theme.command("  $CLI_EXECUTABLE_NAME smoke --workspace-root=/absolute/path/to/workspace --file=CliCommandCatalog.kt"))
            appendLine(theme.command("  source <($CLI_EXECUTABLE_NAME completion bash)"))
        }
    }.trimEnd()

    private fun commandHelp(
        metadata: CliCommandMetadata,
        version: String,
        theme: CliTextTheme,
    ): String = buildString {
        appendLine(theme.title("Kast CLI $version"))
        appendLine()
        appendLine(theme.command("$CLI_EXECUTABLE_NAME ${metadata.commandText}"))
        appendLine(metadata.summary)
        appendLine()
        appendLine(theme.muted(metadata.description))
        appendLine()
        appendSection(
            title = "Usage",
            theme = theme,
        ) {
            metadata.usages.forEach { usage -> appendLine(theme.command("  $usage")) }
        }
        if (metadata.options.isNotEmpty()) {
            appendLine()
            appendSection(
                title = "Options",
                theme = theme,
            ) {
                append(renderOptionTable(metadata.options, theme))
            }
        }
        if (metadata.examples.isNotEmpty()) {
            appendLine()
            appendSection(
                title = "Examples",
                theme = theme,
            ) {
                metadata.examples.forEach { example -> appendLine(theme.command("  $example")) }
            }
        }
    }.trimEnd()

    private fun namespaceHelp(
        prefix: List<String>,
        matches: List<CliCommandMetadata>,
        version: String,
        theme: CliTextTheme,
    ): String = buildString {
        appendLine(theme.title("Kast CLI $version"))
        appendLine()
        appendLine(theme.command("$CLI_EXECUTABLE_NAME ${prefix.joinToString(" ")}"))
        appendLine(theme.muted(matches.first().group.overview))
        appendLine()
        appendSection(
            title = "Usage",
            theme = theme,
        ) {
            appendLine(theme.command("  $CLI_EXECUTABLE_NAME ${prefix.joinToString(" ")} <subcommand> [options]"))
        }
        appendLine()
        appendSection(
            title = "Subcommands",
            theme = theme,
        ) {
            append(renderCommandTable(matches, theme, prefix.size))
        }
        val firstExample = matches.firstNotNullOfOrNull { metadata -> metadata.examples.firstOrNull() }
        if (firstExample != null) {
            appendLine()
            appendSection(
                title = "Try",
                theme = theme,
            ) {
                appendLine(theme.command("  $firstExample"))
            }
        }
    }.trimEnd()

    private fun renderBuiltinTable(theme: CliTextTheme): String {
        val columnWidth = (builtins.maxOfOrNull { builtin -> builtin.usage.length } ?: 0) + 2
        return builtins.joinToString(separator = "\n", postfix = "\n") { builtin ->
            "  ${theme.command(builtin.usage.padEnd(columnWidth))}${builtin.summary}"
        }
    }

    private fun renderCommandTable(
        metadata: List<CliCommandMetadata>,
        theme: CliTextTheme,
        dropSegments: Int = 0,
    ): String {
        val displayNames = metadata.map { command ->
            command.path.drop(dropSegments).joinToString(" ")
        }
        val columnWidth = (displayNames.maxOfOrNull(String::length) ?: 0) + 2
        return metadata.zip(displayNames).joinToString(separator = "\n", postfix = "\n") { (command, displayName) ->
            "  ${theme.command(displayName.padEnd(columnWidth))}${command.summary}"
        }
    }

    private fun renderOptionTable(
        options: List<CliOptionMetadata>,
        theme: CliTextTheme,
    ): String {
        val columnWidth = (options.maxOfOrNull { option -> option.usage.length } ?: 0) + 2
        return options.joinToString(separator = "\n", postfix = "\n") { option ->
            "  ${theme.option(option.usage.padEnd(columnWidth))}${option.description}"
        }
    }

    private fun StringBuilder.appendSection(
        title: String,
        theme: CliTextTheme,
        overview: String? = null,
        content: StringBuilder.() -> Unit,
    ) {
        appendLine(theme.heading(title))
        overview?.let { description ->
            appendLine(theme.muted(description))
        }
        content()
    }
}

internal fun currentCliVersion(): String {
    return KastCli::class.java.`package`.implementationVersion
        ?: System.getProperty("io.github.amichne.kast.version")
        ?: "dev"
}

private fun List<String>.isPrefixOf(other: List<String>): Boolean {
    if (size > other.size) {
        return false
    }
    return indices.all { index -> this[index] == other[index] }
}
