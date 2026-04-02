package io.github.amichne.kast.cli

private const val CLI_EXECUTABLE_NAME = "analysis-cli"

internal data class CliOptionMetadata(
    val usage: String,
    val description: String,
)

internal data class CliCommandMetadata(
    val path: List<String>,
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
    private val workspaceRootOption = CliOptionMetadata(
        usage = "--workspace-root=/absolute/path/to/workspace",
        description = "Absolute workspace root to inspect, ensure, or analyze.",
    )
    private val waitTimeoutOption = CliOptionMetadata(
        usage = "--wait-timeout-ms=60000",
        description = "Maximum time to wait for a ready daemon when a command needs one.",
    )
    private val requestFileOption = CliOptionMetadata(
        usage = "--request-file=/absolute/path/to/query.json",
        description = "Absolute JSON request file for operations with richer payloads.",
    )
    private val filePathOption = CliOptionMetadata(
        usage = "--file-path=/absolute/path/to/File.kt",
        description = "Absolute file path for position-based requests.",
    )
    private val offsetOption = CliOptionMetadata(
        usage = "--offset=123",
        description = "Character offset inside the file identified by --file-path.",
    )
    private val filePathsOption = CliOptionMetadata(
        usage = "--file-paths=/absolute/A.kt,/absolute/B.kt",
        description = "Comma-separated absolute file paths for diagnostics.",
    )
    private val includeDeclarationOption = CliOptionMetadata(
        usage = "--include-declaration=true",
        description = "Include the declaration alongside reference results. Defaults to false.",
    )
    private val newNameOption = CliOptionMetadata(
        usage = "--new-name=RenamedSymbol",
        description = "Replacement symbol name for rename planning.",
    )
    private val dryRunOption = CliOptionMetadata(
        usage = "--dry-run=true",
        description = "Keep rename in planning mode. Defaults to true.",
    )

    private val commands: List<CliCommandMetadata> = listOf(
        CliCommandMetadata(
            path = listOf("workspace", "status"),
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
            path = listOf("daemon", "start"),
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
            path = listOf("diagnostics"),
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
            path = listOf("internal", "daemon-run"),
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

    fun commandsUnder(prefix: List<String>): List<CliCommandMetadata> = commands
        .filter(CliCommandMetadata::visible)
        .filter { prefix.isPrefixOf(it.path) }

    fun topLevelUsageDetails(): Map<String, String> = mapOf(
        "usage" to "$CLI_EXECUTABLE_NAME <command> [options]",
        "help" to "$CLI_EXECUTABLE_NAME help",
        "commands" to commands.filter(CliCommandMetadata::visible).joinToString(", ") { it.commandText },
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

    fun helpText(
        topic: List<String>,
        version: String = currentCliVersion(),
    ): String {
        if (topic.isEmpty()) {
            return topLevelHelp(version)
        }

        val exact = find(topic)
        if (exact != null && exact.visible) {
            return commandHelp(exact, version)
        }

        val namespaceCommands = commandsUnder(topic)
        if (namespaceCommands.isNotEmpty()) {
            return namespaceHelp(topic, namespaceCommands, version)
        }

        return buildString {
            appendLine("Kast CLI $version")
            appendLine()
            appendLine("Unknown command topic: ${topic.joinToString(" ")}")
            appendLine("Use `$CLI_EXECUTABLE_NAME help` for the full command list.")
        }.trimEnd()
    }

    private fun topLevelHelp(version: String): String = buildString {
        appendLine("Kast CLI $version")
        appendLine()
        appendLine("Repo-local control plane for Kast workspace daemons and analysis requests.")
        appendLine()
        appendLine("Usage:")
        appendLine("  $CLI_EXECUTABLE_NAME <command> [options]")
        appendLine("  $CLI_EXECUTABLE_NAME help [command...]")
        appendLine("  $CLI_EXECUTABLE_NAME --help")
        appendLine("  $CLI_EXECUTABLE_NAME --version")
        appendLine()
        appendLine("Commands:")
        append(renderCommandTable(commands.filter(CliCommandMetadata::visible)))
        appendLine()
        appendLine("Notes:")
        appendLine("  - Successful command results stay machine-readable JSON on stdout.")
        appendLine("  - Daemon status notes, when available, are written to stderr after the command.")
        appendLine("  - Command options use --key=value syntax.")
        appendLine()
        appendLine("Try:")
        appendLine("  $CLI_EXECUTABLE_NAME workspace status --workspace-root=/absolute/path/to/workspace")
        appendLine("  $CLI_EXECUTABLE_NAME diagnostics --workspace-root=/absolute/path/to/workspace --request-file=/absolute/path/to/query.json")
        appendLine("  $CLI_EXECUTABLE_NAME help workspace")
    }.trimEnd()

    private fun commandHelp(
        metadata: CliCommandMetadata,
        version: String,
    ): String = buildString {
        appendLine("Kast CLI $version")
        appendLine()
        appendLine("$CLI_EXECUTABLE_NAME ${metadata.commandText}")
        appendLine(metadata.summary)
        appendLine()
        appendLine(metadata.description)
        appendLine()
        appendLine("Usage:")
        metadata.usages.forEach { usage -> appendLine("  $usage") }
        if (metadata.options.isNotEmpty()) {
            appendLine()
            appendLine("Options:")
            metadata.options.forEach { option ->
                appendLine("  ${option.usage.padEnd(42)} ${option.description}")
            }
        }
        if (metadata.examples.isNotEmpty()) {
            appendLine()
            appendLine("Examples:")
            metadata.examples.forEach { example -> appendLine("  $example") }
        }
    }.trimEnd()

    private fun namespaceHelp(
        prefix: List<String>,
        matches: List<CliCommandMetadata>,
        version: String,
    ): String = buildString {
        appendLine("Kast CLI $version")
        appendLine()
        appendLine("$CLI_EXECUTABLE_NAME ${prefix.joinToString(" ")}")
        appendLine()
        appendLine("Usage:")
        appendLine("  $CLI_EXECUTABLE_NAME ${prefix.joinToString(" ")} <subcommand> [options]")
        appendLine()
        appendLine("Subcommands:")
        append(renderCommandTable(matches, prefix.size))
        val firstExample = matches.firstNotNullOfOrNull { it.examples.firstOrNull() }
        if (firstExample != null) {
            appendLine()
            appendLine("Try:")
            appendLine("  $firstExample")
        }
    }.trimEnd()

    private fun renderCommandTable(
        metadata: List<CliCommandMetadata>,
        dropSegments: Int = 0,
    ): String {
        val displayNames = metadata.map { command ->
            command.path.drop(dropSegments).joinToString(" ")
        }
        val columnWidth = (displayNames.maxOfOrNull(String::length) ?: 0) + 2
        return metadata.zip(displayNames).joinToString(separator = "\n", postfix = "\n") { (command, displayName) ->
            "  ${displayName.padEnd(columnWidth)}${command.summary}"
        }
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
