package io.github.amichne.kast.distribution.cli

import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

internal fun runGuidanceProvider(
    arguments: List<String>,
    environment: Map<String, String>,
    timeoutSeconds: Long = PROVIDER_COMMAND_SECONDS,
): ProcessObservation {
    val output =
        try {
            Files.createTempFile("kast-provider-", ".out")
        } catch (_: IOException) {
            return ProcessObservation.Unavailable
        }
    return try {
        val process =
            ProcessBuilder(arguments)
                .redirectOutput(output.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .apply { environment().putAll(environment) }
                .start()
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(CHILD_REAP_SECONDS, TimeUnit.SECONDS)
                ProcessObservation.Unavailable
            } else if (Files.size(output) > PROVIDER_REPLY_BYTES) ProcessObservation.Unavailable
            else ProcessObservation.Exited(process.exitValue(), Files.readString(output))
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            ProcessObservation.Unavailable
        }
    } catch (_: IOException) {
        ProcessObservation.Unavailable
    } finally {
        Files.deleteIfExists(output)
    }
}

internal class GuidanceProviderExecution(
    val command: (List<String>) -> ProcessObservation,
    val dependency: (List<String>, Map<String, String>) -> ProcessObservation,
) {
    companion object {
        fun native(environment: Map<String, String>): GuidanceProviderExecution =
            GuidanceProviderExecution(
                command = { runGuidanceProvider(it, environment) },
                dependency = { arguments, selected ->
                    runGuidanceProvider(arguments, environment + selected, MCP_SETUP_SECONDS)
                },
            )
    }
}

private const val PROVIDER_COMMAND_SECONDS = 8L
internal const val MCP_SETUP_SECONDS = 60L
private const val CHILD_REAP_SECONDS = 1L
private const val PROVIDER_REPLY_BYTES = 262_144L
