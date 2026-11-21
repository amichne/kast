package io.github.amichne.kast.distribution.cli

import java.util.concurrent.TimeUnit

private const val BOUNDED_PROCESS_SECONDS = 8L
private const val BOUNDED_PROCESS_OUTPUT_BYTES = 262144

internal sealed interface ProcessObservation {
    data class Exited(val code: Int, val output: String) : ProcessObservation

    data object Unavailable : ProcessObservation
}

/** Runs a short read-only observation command with a bounded deadline and output. */
internal fun runBounded(arguments: List<String>): ProcessObservation {
    val process =
        try {
            ProcessBuilder(arguments).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (_: Exception) {
            return ProcessObservation.Unavailable
        }
    return try {
        if (!process.waitFor(BOUNDED_PROCESS_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            ProcessObservation.Unavailable
        } else {
            val output = process.inputStream.readNBytes(BOUNDED_PROCESS_OUTPUT_BYTES + 1)
            if (output.size > BOUNDED_PROCESS_OUTPUT_BYTES) ProcessObservation.Unavailable
            else ProcessObservation.Exited(process.exitValue(), output.decodeToString())
        }
    } catch (_: Exception) {
        process.destroyForcibly()
        ProcessObservation.Unavailable
    }
}
