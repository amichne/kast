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
        overview = "Read-only commands for capabilities, symbols, references, call hierarchy, and diagnostics.",
    ),
    MUTATION_FLOW(
        title = "Mutation flow",
        overview = "Plan or apply code edits through the daemon's supported mutation pipeline.",
    ),
    SHELL_INTEGRATION(
        title = "Shell integration",
        overview = "Opt-in helpers for interactive terminals that keep the public command tree easy to drive.",
    ),
    CLI_MANAGEMENT(
        title = "CLI management",
        overview = "Install and manage local Kast CLI instances.",
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
        description = "Comma-separated absolute file paths for diagnostics.",
        completionKind = CliOptionCompletionKind.FILE_LIST,
    )
    private val includeDeclarationOption = CliOptionMetadata(
        key = "include-declaration",
        usage = "--include-declaration=true",
        description = "Include the declaration alongside reference results. Defaults to false.",
        completionKind = CliOptionCompletionKind.BOOLEAN,
    )
    private val directionOption = CliOptionMetadata(
        key = "direction",
        usage = "--direction=incoming",
        description = "Traversal direction for call hierarchy. Use incoming or outgoing.",
    )
    private val depthOption = CliOptionMetadata(
        key = "depth",
        usage = "--depth=3",
        description = "Maximum edge depth to expand from the selected declaration. Defaults to 3.",
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
    private val persistToGitShaCacheOption = CliOptionMetadata(
        key = "persist-to-git-sha-cache",
        usage = "--persist-to-git-sha-cache=true",
        description = "Persist the result under a git-SHA-scoped cache when the workspace has a git commit. Defaults to false.",
        completionKind = CliOptionCompletionKind.BOOLEAN,
    )
    private val newNameOption = CliOptionMetadata(
        key = "new-name",
        usage = "--new-name=RenamedSymbol",
        description = "Replacement symbol name for rename planning.",
    )
    private val dryRunOption = CliOptionMetadata(
        key = "dry-run",
        usage = "--dry-run=true",
        description = "Keep rename in planning mode. Defaults to true.",
        completionKind = CliOptionCompletionKind.BOOLEAN,
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
        description = "Directory to create the skill symlink in. Auto-detected from CWD when omitted.",
        completionKind = CliOptionCompletionKind.DIRECTORY,
    )
    private val skillLinkNameOption = CliOptionMetadata(
        key = "link-name",
        usage = "--link-name=kast",
        description = "Name for the skill symlink. Defaults to kast.",
    )
    private val yesOption = CliOptionMetadata(
        key = "yes",
        usage = "--yes=true",
        description = "Replace an existing symlink without prompting. Defaults to false.",
        completionKind = CliOptionCompletionKind.BOOLEAN,
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
            options = listOf(workspaceRootOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME workspace status --workspace-root=/absolute/path/to/workspace",
            ),
        ),
        CliCommandMetadata(
            path = listOf("workspace", "ensure"),
            group = CliCommandGroup.WORKSPACE_LIFECYCLE,
            summary = "Reuse a ready daemon or start one for the workspace.",
            description = "Ensures a healthy standalone daemon exists before returning runtime metadata.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME workspace ensure --workspace-root=/absolute/path/to/workspace [--wait-timeout-ms=60000]",
            ),
            options = listOf(workspaceRootOption, waitTimeoutOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME workspace ensure --workspace-root=/absolute/path/to/workspace",
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
            options = listOf(workspaceRootOption, waitTimeoutOption, requestFileOption, filePathsOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME workspace refresh --workspace-root=/absolute/path/to/workspace",
                "$CLI_EXECUTABLE_NAME workspace refresh --workspace-root=/absolute/path/to/workspace --file-paths=/absolute/path/to/File.kt",
            ),
        ),
        CliCommandMetadata(
            path = listOf("daemon", "start"),
            group = CliCommandGroup.WORKSPACE_LIFECYCLE,
            summary = "Start a detached standalone daemon for a workspace.",
            description = "Starts a standalone daemon when none is ready, then waits until it reports readiness.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME daemon start --workspace-root=/absolute/path/to/workspace [--wait-timeout-ms=60000]",
            ),
            options = listOf(workspaceRootOption, waitTimeoutOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME daemon start --workspace-root=/absolute/path/to/workspace",
            ),
        ),
        CliCommandMetadata(
            path = listOf("daemon", "stop"),
            group = CliCommandGroup.WORKSPACE_LIFECYCLE,
            summary = "Stop the standalone daemon registered for a workspace.",
            description = "Stops the selected daemon, removes its descriptor, and reports what was stopped.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME daemon stop --workspace-root=/absolute/path/to/workspace",
            ),
            options = listOf(workspaceRootOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME daemon stop --workspace-root=/absolute/path/to/workspace",
            ),
        ),
        CliCommandMetadata(
            path = listOf("capabilities"),
            group = CliCommandGroup.ANALYSIS,
            summary = "Print the advertised capabilities for the ready workspace daemon.",
            description = "Ensures the workspace has a ready daemon, then returns its current capability set as JSON.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME capabilities --workspace-root=/absolute/path/to/workspace [--wait-timeout-ms=60000]",
            ),
            options = listOf(workspaceRootOption, waitTimeoutOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME capabilities --workspace-root=/absolute/path/to/workspace",
            ),
        ),
        CliCommandMetadata(
            path = listOf("symbol", "resolve"),
            group = CliCommandGroup.ANALYSIS,
            summary = "Resolve the symbol at a file position.",
            description = "Accepts either an absolute request file or inline file position arguments.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME symbol resolve --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json",
                "$CLI_EXECUTABLE_NAME symbol resolve --workspace-root=/absolute/path/to/workspace --file-path=/absolute/path/to/File.kt --offset=123",
            ),
            options = listOf(workspaceRootOption, waitTimeoutOption, requestFileOption, filePathOption, offsetOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME symbol resolve --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json",
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
                waitTimeoutOption,
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
            path = listOf("call", "hierarchy"),
            group = CliCommandGroup.ANALYSIS,
            summary = "Expand a bounded call hierarchy for the symbol at a file position.",
            description = "Accepts either an absolute request file or inline position, direction, and bound arguments.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME call hierarchy --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json",
                "$CLI_EXECUTABLE_NAME call hierarchy --workspace-root=/absolute/path/to/workspace --file-path=/absolute/path/to/File.kt --offset=123 --direction=incoming [--depth=3] [--max-total-calls=256] [--max-children-per-node=64] [--timeout-millis=5000] [--persist-to-git-sha-cache=true]",
            ),
            options = listOf(
                workspaceRootOption,
                waitTimeoutOption,
                requestFileOption,
                filePathOption,
                offsetOption,
                directionOption,
                depthOption,
                maxTotalCallsOption,
                maxChildrenPerNodeOption,
                timeoutMillisOption,
                persistToGitShaCacheOption,
            ),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME call hierarchy --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json",
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
            options = listOf(workspaceRootOption, waitTimeoutOption, requestFileOption, filePathsOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME diagnostics --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json",
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
                waitTimeoutOption,
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
            path = listOf("edits", "apply"),
            group = CliCommandGroup.MUTATION_FLOW,
            summary = "Apply a prepared edit plan.",
            description = "Requires an absolute request file that contains the edits and expected file hashes.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME edits apply --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json",
            ),
            options = listOf(workspaceRootOption, waitTimeoutOption, requestFileOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME edits apply --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json",
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
            summary = "Link the packaged kast skill into the current workspace.",
            description = "Creates a symlink to the bundled kast skill in the nearest recognised skills directory (.agents/skills, .github/skills, or .claude/skills), or the path given by --target-dir. Set KAST_SKILL_PATH to override the packaged skill source.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME install skill [--target-dir=/absolute/path/to/skills] [--link-name=kast] [--yes=true]",
            ),
            options = listOf(skillTargetDirOption, skillLinkNameOption, yesOption),
            examples = listOf(
                "$CLI_EXECUTABLE_NAME install skill",
                "$CLI_EXECUTABLE_NAME install skill --target-dir=/my/project/.agents/skills",
                "$CLI_EXECUTABLE_NAME install skill --yes=true",
            ),
        ),
        CliCommandMetadata(
            path = listOf("internal", "daemon-run"),
            group = CliCommandGroup.WORKSPACE_LIFECYCLE,
            summary = "Internal detached daemon entrypoint.",
            description = "Bootstraps the standalone daemon process that the public CLI manages.",
            usages = listOf(
                "$CLI_EXECUTABLE_NAME internal daemon-run --workspace-root=/absolute/path/to/workspace",
            ),
            visible = false,
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
            appendLine("  Daemon lifecycle notes, when present, stay on stderr.")
            appendLine("  Every command option uses --key=value syntax.")
        }
        appendLine()
        appendSection(
            title = "Try",
            theme = theme,
        ) {
            appendLine(theme.command("  $CLI_EXECUTABLE_NAME workspace ensure --workspace-root=/absolute/path/to/workspace"))
            appendLine(theme.command("  $CLI_EXECUTABLE_NAME diagnostics --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json"))
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
