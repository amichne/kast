package io.github.amichne.kast.cli.installation

import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal enum class LegacyHelperFailure {
    START_FAILED,
    TIMED_OUT,
    CAPTURE_FAILED,
    OUTPUT_LIMIT_EXCEEDED,
    INTERRUPTED,
}

internal sealed interface LegacyHelperRead {
    data class Read(val bytes: ByteArray) : LegacyHelperRead

    data class Unavailable(val reason: LegacyHelperFailure) : LegacyHelperRead

    data class Exited(val exitCode: Int) : LegacyHelperRead
}

internal fun boundedLegacyHelper(arguments: List<String>, maximumBytes: Int = 1_048_576): LegacyHelperRead {
    val child =
        try {
            val builder = ProcessBuilder(arguments).redirectError(ProcessBuilder.Redirect.DISCARD)
            for (name in listOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_OPTS")) builder
                .environment()
                .remove(name)
            builder.start()
        } catch (_: IOException) {
            return LegacyHelperRead.Unavailable(LegacyHelperFailure.START_FAILED)
        }
    return Executors.newVirtualThreadPerTaskExecutor().use { executor ->
        val capture = executor.submit<ByteArray> { child.inputStream.use { it.readNBytes(maximumBytes + 1) } }
        try {
            readLegacyHelper(child, capture, maximumBytes)
        } catch (_: TimeoutException) {
            child.destroyForcibly()
            LegacyHelperRead.Unavailable(LegacyHelperFailure.TIMED_OUT)
        } catch (_: java.util.concurrent.ExecutionException) {
            LegacyHelperRead.Unavailable(LegacyHelperFailure.CAPTURE_FAILED)
        } catch (_: InterruptedException) {
            child.destroyForcibly()
            Thread.currentThread().interrupt()
            LegacyHelperRead.Unavailable(LegacyHelperFailure.INTERRUPTED)
        } finally {
            capture.cancel(true)
        }
    }
}

private fun readLegacyHelper(
    child: Process,
    capture: java.util.concurrent.Future<ByteArray>,
    maximumBytes: Int,
): LegacyHelperRead {
    if (!child.waitFor(HELPER_SECONDS, TimeUnit.SECONDS)) {
        child.destroyForcibly()
        return LegacyHelperRead.Unavailable(LegacyHelperFailure.TIMED_OUT)
    }
    val bytes = capture.get(HELPER_SECONDS, TimeUnit.SECONDS)
    return when {
        bytes.size > maximumBytes -> LegacyHelperRead.Unavailable(LegacyHelperFailure.OUTPUT_LIMIT_EXCEEDED)
        child.exitValue() != 0 -> LegacyHelperRead.Exited(child.exitValue())
        else -> LegacyHelperRead.Read(bytes)
    }
}

private const val HELPER_SECONDS = 10L
