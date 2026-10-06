package io.github.amichne.kast.distribution.cli

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable internal data class CodexServer(val name: String, val transport: CodexTransport)

@Serializable
internal data class CodexTransport(val type: String, val command: String = "", val args: List<String> = emptyList())

private val codexJson = Json { ignoreUnknownKeys = true }
private const val CODEX_DEADLINE_SECONDS = 8L
private const val CODEX_OUTPUT_LIMIT_BYTES = 262144

internal sealed interface ProcessObservation {
    data class Exited(val code: Int, val output: String) : ProcessObservation

    data object Unavailable : ProcessObservation
}

internal fun runBounded(arguments: List<String>, codexHome: Path? = null): ProcessObservation {
    val process =
        try {
            ProcessBuilder(arguments)
                .apply {
                    if (codexHome != null) environment()["CODEX_HOME"] = codexHome.toString()
                }
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (_: Exception) {
            return ProcessObservation.Unavailable
        }
    return try {
        if (!process.waitFor(CODEX_DEADLINE_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            ProcessObservation.Unavailable
        } else {
            val output = process.inputStream.readNBytes(CODEX_OUTPUT_LIMIT_BYTES + 1)
            if (output.size > CODEX_OUTPUT_LIMIT_BYTES) ProcessObservation.Unavailable
            else ProcessObservation.Exited(process.exitValue(), output.decodeToString())
        }
    } catch (_: Exception) {
        process.destroyForcibly()
        ProcessObservation.Unavailable
    }
}

@Suppress("ThrowsCount")
internal fun codexRegistration(execute: (List<String>) -> ProcessObservation = { runBounded(it) }): CodexServer? {
    val response = execute(listOf("codex", "mcp", "list", "--json"))
    if (response !is ProcessObservation.Exited || response.code != 0)
        throw ManagementRejected("registration-inspection", "Codex configuration is unavailable")
    val entries =
        try {
            codexJson.decodeFromString<List<CodexServer>>(response.output)
        } catch (_: SerializationException) {
            throw ManagementRejected("registration-inspection", "Codex configuration is invalid")
        }
    val matches = entries.filter { it.name == "kast" }
    if (matches.size > 1) throw ManagementRejected("registration-inspection", "Codex registration is ambiguous")
    return matches.singleOrNull()
}

internal fun ownsCodex(server: CodexServer?, destination: Path): Boolean =
    server != null &&
        server.transport.type == "stdio" &&
        server.transport.command == destination.toString() &&
        server.transport.args.isEmpty()
