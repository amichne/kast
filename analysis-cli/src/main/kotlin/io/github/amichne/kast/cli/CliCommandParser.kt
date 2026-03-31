package io.github.amichne.kast.cli

import io.github.amichne.kast.api.ApplyEditsQuery
import io.github.amichne.kast.api.DiagnosticsQuery
import io.github.amichne.kast.api.FilePosition
import io.github.amichne.kast.api.ReferencesQuery
import io.github.amichne.kast.api.RenameQuery
import io.github.amichne.kast.api.SymbolQuery
import io.github.amichne.kast.standalone.StandaloneServerOptions
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.readText

internal class CliCommandParser(
    private val json: Json,
) {
    fun parse(args: Array<String>): CliCommand {
        val parsed = ParsedArguments.parse(args)
        return when (parsed.positionals) {
            listOf("workspace", "status") -> CliCommand.WorkspaceStatus(parsed.runtimeOptions())
            listOf("workspace", "ensure") -> CliCommand.WorkspaceEnsure(parsed.runtimeOptions())
            listOf("daemon", "start") -> CliCommand.DaemonStart(parsed.runtimeOptions(backendName = "standalone"))
            listOf("daemon", "stop") -> CliCommand.DaemonStop(parsed.runtimeOptions(backendName = "standalone"))
            listOf("capabilities") -> CliCommand.Capabilities(parsed.runtimeOptions())
            listOf("symbol", "resolve") -> CliCommand.ResolveSymbol(parsed.runtimeOptions(), parsed.symbolQuery(json))
            listOf("references") -> CliCommand.FindReferences(parsed.runtimeOptions(), parsed.referencesQuery(json))
            listOf("diagnostics") -> CliCommand.Diagnostics(parsed.runtimeOptions(), parsed.diagnosticsQuery(json))
            listOf("rename") -> CliCommand.Rename(parsed.runtimeOptions(), parsed.renameQuery(json))
            listOf("edits", "apply") -> CliCommand.ApplyEdits(parsed.runtimeOptions(), parsed.applyEditsQuery(json))
            listOf("internal", "daemon-run") -> CliCommand.InternalDaemonRun(parsed.runtimeOptions(backendName = "standalone"))
            else -> throw CliFailure(
                code = "CLI_USAGE",
                message = "Unknown command: ${args.joinToString(" ")}",
            )
        }
    }
}

internal data class ParsedArguments(
    val positionals: List<String>,
    val options: Map<String, String>,
) {
    companion object {
        fun parse(args: Array<String>): ParsedArguments {
            val positionals = mutableListOf<String>()
            val options = linkedMapOf<String, String>()
            args.forEach { argument ->
                if (argument.startsWith("--")) {
                    val parts = argument.removePrefix("--").split("=", limit = 2)
                    if (parts.size != 2 || parts[0].isBlank()) {
                        throw CliFailure(
                            code = "CLI_USAGE",
                            message = "Arguments must use --key=value syntax: $argument",
                        )
                    }
                    options[parts[0]] = parts[1]
                } else {
                    positionals += argument
                }
            }
            return ParsedArguments(positionals = positionals, options = options)
        }
    }

    fun runtimeOptions(backendName: String? = options["backend-name"]): RuntimeCommandOptions {
        val standaloneOptions = StandaloneServerOptions.fromValues(options)
        return RuntimeCommandOptions(
            workspaceRoot = standaloneOptions.workspaceRoot,
            backendName = backendName,
            waitTimeoutMillis = options["wait-timeout-ms"]?.toLongOrNull() ?: 60_000L,
            standaloneOptions = standaloneOptions,
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
            message = "`edits apply` requires --request-file=/absolute/path/to/query.json",
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

    private fun optionalBoolean(
        key: String,
        default: Boolean,
    ): Boolean = options[key]?.toBooleanStrictOrNull() ?: default

    private fun absoluteFilePath(value: String): String = Path.of(value).toAbsolutePath().normalize().toString()
}
