package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.distribution.contract.ObservedProcessState
import io.github.amichne.kast.distribution.contract.admitObservedProcessState
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal fun observeManagedProcessState(pid: Long): ObservedProcessState {
    val child =
        try {
            ProcessBuilder("/bin/ps", "-p", pid.toString(), "-o", "stat=")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (_: IOException) {
            return ObservedProcessState.UNAVAILABLE
        }
    return Executors.newVirtualThreadPerTaskExecutor().use { executor ->
        val capture =
            executor.submit<ByteArray> {
                child.inputStream.use { it.readNBytes(MAXIMUM_STATE_BYTES + 1) }
            }
        try {
            readManagedProcessState(child, capture)
        } catch (_: TimeoutException) {
            child.destroyForcibly()
            ObservedProcessState.UNAVAILABLE
        } catch (_: ExecutionException) {
            ObservedProcessState.UNAVAILABLE
        } catch (_: InterruptedException) {
            child.destroyForcibly()
            Thread.currentThread().interrupt()
            ObservedProcessState.UNAVAILABLE
        } finally {
            capture.cancel(true)
        }
    }
}

private fun readManagedProcessState(child: Process, capture: Future<ByteArray>): ObservedProcessState {
    if (!child.waitFor(STATE_HELPER_SECONDS, TimeUnit.SECONDS)) {
        child.destroyForcibly()
        return ObservedProcessState.UNAVAILABLE
    }
    val bytes = capture.get(STATE_HELPER_SECONDS, TimeUnit.SECONDS)
    if (bytes.size > MAXIMUM_STATE_BYTES) return ObservedProcessState.UNAVAILABLE
    return admitObservedProcessState(child.exitValue(), bytes.decodeToString())
}

private const val MAXIMUM_STATE_BYTES = 128
private const val STATE_HELPER_SECONDS = 2L
