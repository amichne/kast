package io.github.amichne.kast.cli.tty

import io.github.amichne.kast.cli.KastCli

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
        overview = "Inspect the current backend capability set before sending JSON-RPC analysis requests.",
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
        overview = "Install and manage local Kast CLI instances and configuration.",
    ),
    RPC(
        title = "RPC",
        overview = "JSON-RPC passthrough to the workspace daemon. Auto-ensures the daemon on each request.",
    ),
    GRADLE(
        title = "Gradle",
        overview = "Run Gradle tasks with structured JSON output and raw build logs kept on disk.",
    ),
}

internal enum class CliOptionCompletionKind {
    NONE,
    DIRECTORY,
    FILE,
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

private const val KAST_ROOT_DIR = "~/.kast"

private const val MANIFEST_FILE = ".manifest.json"
private const val KAST_COPILOT_VERSION = ".kast-copilot-version"

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
            "Start a backend first with `kast daemon start --workspace-root=<path>` or open the project in IntelliJ with the Kast plugin installed.",
    )
    private val workspaceRootOption = CliOptionMetadata(
        key = "workspace-root",
        usage = "--workspace-root=/absolute/path/to/workspace",
        description = "Absolute workspace root for daemon lifecycle and RPC commands.",
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
        description = "Allow `up` to return once the daemon is servable in INDEXING. Defaults to false.",
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
        description = "Root directory for instances. Defaults to $KAST_ROOT_DIR/releases.",
        completionKind = CliOptionCompletionKind.DIRECTORY,
    )
    private val binDirOption = CliOptionMetadata(
        key = "bin-dir",
        usage = "--bin-dir=/absolute/path/to/bin",
        description = "Directory for launcher scripts. Defaults to $KAST_ROOT_DIR/bin.",
        completionKind = CliOptionCompletionKind.DIRECTORY,
    )
    private val skillTargetDirOption = CliOptionMetadata(
        key = "target-dir",
        usage = "--target-dir=/absolute/path/to/skills",
        description = "Directory to install the packaged skill in. Auto-detected from CWD when omitted, or $KAST_ROOT_DIR/lib/skills when no workspace skills directory exists.",
        completionKind = CliOptionCompletionKind.DIRECTORY,
    )
    private val copilotTargetDirOption = CliOptionMetadata(
        key = "target-dir",
        usage = "--target-dir=/absolute/path/to/workspace/.github",
        description = "Workspace .github directory to install the packaged Copilot agents and hooks into. Defaults to <cwd>/.github.",
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
    private val uninstallOption = CliOptionMetadata(
        key = "uninstall",
        usage = "--uninstall=true",
        description = "Remove packaged files and the version marker instead of installing. Defaults to false.",
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
            "Defaults to backends.standalone.runtimeLibsDir in config.toml.",
        completionKind = CliOptionCompletionKind.DIRECTORY,
    )
    private val gradleArgsOption = CliOptionMetadata(
        key = "args",
        usage = "--args=--stacktrace,--info",
        description = "Optional comma-separated Gradle arguments forwarded after the task name.",
    )

    private val commands: List<CliCommandMetadata> = listOf(
        CliCommandMetadata(
            path = listOf("daemon", "start"),
            group = CliCommandGroup.WORKSPACE_LIFECYCLE,
            summary = "Start the standalone JVM backend for a workspace.",
            description = "Launches the standalone JVM backend process for the given workspace. " +
                "The process runs in the foreground; use a terminal multiplexer or background shell job to keep it alive. " +
                "The backend runtime-libs are located from backends.standalone.runtimeLibsDir in config.toml. " +
                "Pass --runtime-libs-dir to override the configured path. " +
                "Pass profiling options to temporarily override the profiling config for this daemon process. " +
                "All other options are forwarded verbatim to the backend process. " +
                "Once running, send `$CLI_EXECUTABLE_NAME up --workspace-root=<path>` to verify it is ready.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME daemon start --workspace-root=/absolute/path/to/workspace [--socket-path=...] [--runtime-libs-dir=...] [--profile] [--profile-modes=cpu,alloc]",
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
                CliOptionMetadata(
                    key = "profile",
                    usage = "--profile",
                    description = "Enable profiling for this daemon process using the configured or overridden profiling settings.",
                ),
                CliOptionMetadata(
                    key = "profile-modes",
                    usage = "--profile-modes=cpu,alloc",
                    description = "Comma-separated profiling modes to enable for this daemon process.",
                ),
                CliOptionMetadata(
                    key = "profile-duration",
                    usage = "--profile-duration=45",
                    description = "Profiling duration in seconds for each requested mode.",
                ),
                CliOptionMetadata(
                    key = "profile-otlp-endpoint",
                    usage = "--profile-otlp-endpoint=http://localhost:4317",
                    description = "OTLP endpoint override for telemetry export while profiling is enabled.",
                ),
            ),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME daemon start --workspace-root=/absolute/path/to/workspace",
                "$CLI_EXECUTABLE_NAME daemon start --workspace-root=/absolute/path/to/workspace --module-name=myApp",
                "$CLI_EXECUTABLE_NAME daemon start --workspace-root=/absolute/path/to/workspace --runtime-libs-dir=/path/to/runtime-libs",
                "$CLI_EXECUTABLE_NAME daemon start --workspace-root=/absolute/path/to/workspace --profile --profile-modes=cpu,alloc --profile-duration=45",
            ),
        ),
        CliCommandMetadata(
            path = listOf("config", "init"),
            group = CliCommandGroup.CLI_MANAGEMENT,
            summary = "Write a default Kast config file.",
            description = "Creates config.toml under the Kast config home with all supported options documented and commented out.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME config init",
            ),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME config init",
            ),
        ),
        CliCommandMetadata(
            path = listOf("gradle", "run"),
            group = CliCommandGroup.GRADLE,
            summary = "Run a Gradle task and print structured JSON.",
            description = "Runs one Gradle task from the workspace root, writes raw Gradle output to a log file, and prints a stable JSON summary.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME gradle run :module:test --workspace-root=/absolute/path/to/workspace",
                "$CLI_EXECUTABLE_NAME gradle run --task=:module:test --workspace-root=/absolute/path/to/workspace [--args=--stacktrace,--info]",
            ),
            options = listOf(
                workspaceRootOption,
                CliOptionMetadata(
                    key = "task",
                    usage = "--task=:module:test",
                    description = "Gradle task to run when not passed as a positional argument.",
                ),
                gradleArgsOption,
            ),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME gradle run :kast-cli:test --workspace-root=/absolute/path/to/workspace",
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
                "$CLI_EXECUTABLE_NAME install --archive=/absolute/path/to/kast-portable.zip [--instance=<name>] [--bin-dir=$KAST_ROOT_DIR/bin] [--instances-root=~/.kast/releases]",
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
            summary = "Install the packaged kast into the current workspace.",
            description = "Copies the bundled kast into the nearest recognised skills directory (.agents/skills, .github/skills, or .claude/skills), otherwise $KAST_ROOT_DIR/lib/skills, or the path given by --target-dir. Installed skill trees include a .kast-version marker so matching installs can be skipped safely.",
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
            path = listOf("install", "copilot-extension"),
            group = CliCommandGroup.CLI_MANAGEMENT,
            summary = "Install the kast Copilot agents and hooks into the current workspace.",
            description = "Copies the bundled Copilot agent and hook files into .github, or the path given by --target-dir. Installed extension trees include a $KAST_COPILOT_VERSION marker so matching installs can be skipped safely.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME install copilot-extension [--target-dir=/absolute/path/to/workspace/.github] [--yes=true] [--uninstall=true]",
            ),
            options = listOf(copilotTargetDirOption, yesOption, uninstallOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME install copilot-extension",
                "$CLI_EXECUTABLE_NAME install copilot-extension --target-dir=/my/project/.github",
                "$CLI_EXECUTABLE_NAME install copilot-extension --yes=true",
                "$CLI_EXECUTABLE_NAME install copilot-extension --uninstall=true",
            ),
        ),
        CliCommandMetadata(
            path = listOf("self", "status"),
            group = CliCommandGroup.CLI_MANAGEMENT,
            summary = "Report the recorded global Kast install manifest.",
            description = "Reads $KAST_ROOT_DIR/$MANIFEST_FILE and returns the installed version, components, managed paths, shell patches, and managed repositories.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME self status",
            ),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME self status",
            ),
        ),
        CliCommandMetadata(
            path = listOf("self", "doctor"),
            group = CliCommandGroup.CLI_MANAGEMENT,
            summary = "Verify the global Kast install is still healthy.",
            description = "Checks the install manifest, binary, config.toml, managed paths, Copilot resolve scripts, python3 availability, and runtime libs when the backend is installed.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME self doctor",
            ),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME self doctor",
            ),
        ),
        CliCommandMetadata(
            path = listOf("self", "uninstall"),
            group = CliCommandGroup.CLI_MANAGEMENT,
            summary = "Remove manifest-managed files from the global Kast install.",
            description = "Deletes manifest-managed paths under $KAST_ROOT_DIR, removes recorded shell RC patches, and removes the install root when it becomes empty.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME self uninstall",
            ),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME self uninstall",
            ),
        ),
        CliCommandMetadata(
            path = listOf("self", "upgrade"),
            group = CliCommandGroup.CLI_MANAGEMENT,
            summary = "Detect install method and show the appropriate upgrade path.",
            description = "Reads install metadata and the current environment to print the appropriate upgrade path.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME self upgrade",
            ),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME self upgrade",
            ),
        ),
        CliCommandMetadata(
            path = listOf("verify-extension"),
            group = CliCommandGroup.VALIDATION,
            summary = "Verify the installed Copilot extension version matches this CLI.",
            description = "Reads .github/$KAST_COPILOT_VERSION from the current workspace and compares it with " +
                          "the running kast CLI version. Emits JSON and exits non-zero when the versions drift.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME verify-extension",
            ),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME verify-extension",
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
        CliCommandMetadata(
            path = listOf("rpc"),
            group = CliCommandGroup.RPC,
            summary = "Send a raw JSON-RPC request to the workspace daemon.",
            description = "Forwards a raw JSON-RPC string to the daemon over its Unix domain socket. " +
                "The daemon is auto-ensured before each request. " +
                "Pass the JSON as a positional argument or via --request-file.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME rpc '<json>' [--workspace-root=/absolute/path/to/workspace]",
                "$CLI_EXECUTABLE_NAME rpc --request-file=/absolute/path/to/request.json [--workspace-root=/absolute/path/to/workspace]",
            ),
            options = listOf(workspaceRootOption, requestFileOption),
            examples = listOf(
                """$CLI_EXECUTABLE_NAME rpc '{"jsonrpc":"2.0","method":"health","id":1}' --workspace-root=/absolute/path/to/workspace""",
                "$CLI_EXECUTABLE_NAME rpc --request-file=/tmp/request.json --workspace-root=/absolute/path/to/workspace",
            ),
        ),
        CliCommandMetadata(
            path = listOf("up"),
            group = CliCommandGroup.WORKSPACE_LIFECYCLE,
            summary = "Start or warm the workspace daemon.",
            description = "Ensures a healthy backend is running for the workspace.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME up --workspace-root=/absolute/path/to/workspace [--backend-name=intellij|standalone]",
            ),
            options = listOf(workspaceRootOption, backendNameOption, waitTimeoutOption, acceptIndexingOption, noAutoStartOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME up --workspace-root=/absolute/path/to/workspace",
            ),
        ),
        CliCommandMetadata(
            path = listOf("status"),
            group = CliCommandGroup.WORKSPACE_LIFECYCLE,
            summary = "Check what backends are running.",
            description = "Reports the selected daemon plus any additional descriptors registered for the workspace.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME status --workspace-root=/absolute/path/to/workspace [--backend-name=intellij|standalone]",
            ),
            options = listOf(workspaceRootOption, backendNameOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME status --workspace-root=/absolute/path/to/workspace",
            ),
        ),
        CliCommandMetadata(
            path = listOf("stop"),
            group = CliCommandGroup.WORKSPACE_LIFECYCLE,
            summary = "Stop the workspace daemon.",
            description = "Stops the selected backend, removes its descriptor, and reports what was stopped.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME stop --workspace-root=/absolute/path/to/workspace [--backend-name=standalone|intellij]",
            ),
            options = listOf(workspaceRootOption, backendNameOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME stop --workspace-root=/absolute/path/to/workspace",
            ),
        ),
        CliCommandMetadata(
            path = listOf("eval", "skill"),
            group = CliCommandGroup.VALIDATION,
            summary = "Evaluate the packaged kast for structural quality, budget, and contract compliance.",
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
            appendLine(theme.command("  $CLI_EXECUTABLE_NAME up --workspace-root=/absolute/path/to/workspace --accept-indexing=true"))
            appendLine(theme.command("  $CLI_EXECUTABLE_NAME rpc --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/request.json"))
            appendLine(theme.command("  $CLI_EXECUTABLE_NAME smoke --workspace-root=/absolute/path/to/workspace --file=CliCommandCatalog.kt"))
            appendLine(theme.command("  source <($CLI_EXECUTABLE_NAME completion bash)"))
        }
    }.trimEnd()

    private fun commandHelp(
        metadata: CliCommandMetadata,
        version: String,
        theme: CliTextTheme,
    ): String = buildString {
        val subcommands = commandsUnder(metadata.path)
            .filter { command -> command.path.size > metadata.path.size }
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
        if (subcommands.isNotEmpty()) {
            appendLine()
            appendSection(
                title = "Subcommands",
                theme = theme,
            ) {
                append(renderCommandTable(subcommands, theme, metadata.path.size))
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
