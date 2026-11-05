package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.kernel.Refinement
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

internal data class PublicJqCapture(val childExit: Int, val stdout: String)

internal data class PublicJqProcessRequest(
    val arguments: List<String>,
    val input: String,
    val stage: PublicJqStage,
    val limits: PublicJqLimits,
)

/** Effect seam: observations are captured here; engine admission and result policy remain in PublicJqEngine. */
internal fun interface PublicJqExecutor {
    fun execute(request: PublicJqProcessRequest): Refinement<PublicJqCapture, PublicJqRejection>
}

internal class PublicJqProcess(
    private val directory: Path,
    private val environment: Map<String, String>,
    private val start: (ProcessBuilder) -> Process = ProcessBuilder::start,
) : PublicJqExecutor {
    override fun execute(request: PublicJqProcessRequest): Refinement<PublicJqCapture, PublicJqRejection> {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("jq invocation cancelled before start")
        if (request.input.length > request.limits.bytes) return rejected(request, PublicJqFailure.BYTE_LIMIT)
        val bytes =
            try {
                request.input.encodeToByteArray(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                return rejected(request, PublicJqFailure.OUTPUT_INVALID)
            }
        if (bytes.size > request.limits.bytes) return rejected(request, PublicJqFailure.BYTE_LIMIT)
        val child =
            try {
                start(
                    ProcessBuilder(request.arguments).directory(directory.toFile()).apply {
                        environment().clear()
                        environment().putAll(this@PublicJqProcess.environment)
                    }
                )
            } catch (_: IOException) {
                return rejected(request, PublicJqFailure.ENGINE_UNAVAILABLE)
            } catch (_: SecurityException) {
                return rejected(request, PublicJqFailure.ENGINE_UNAVAILABLE)
            }
        return capture(child, bytes, request)
    }

    private fun capture(
        child: Process,
        input: ByteArray,
        request: PublicJqProcessRequest,
    ): Refinement<PublicJqCapture, PublicJqRejection> {
        val workers =
            Executors.newFixedThreadPool(3) { action ->
                Thread(action, "kast-jq-pipe").apply { isDaemon = true }
            }
        val failure = AtomicReference<PublicJqFailure?>(null)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(request.limits.timeoutMillis)
        try {
            val stdout =
                workers.submit<ByteArray> {
                    read(child.inputStream, request.limits.bytes, child, failure, PublicJqFailure.BYTE_LIMIT)
                }
            val stderr =
                workers.submit<ByteArray> {
                    read(child.errorStream, PublicJqLimits.STDERR_BYTES, child, failure, PublicJqFailure.STDERR_LIMIT)
                }
            val writer = workers.submit {
                try {
                    child.outputStream.use { it.write(input) }
                } catch (_: IOException) {
                    failure.compareAndSet(null, PublicJqFailure.PROCESS_IO)
                }
            }
            if (!child.waitFor(remaining(deadline), TimeUnit.NANOSECONDS))
                return rejected(request, PublicJqFailure.TIME_LIMIT)
            val output = await(stdout, deadline)
            await(stderr, deadline)
            await(writer, deadline)
            val observed = failure.get()
            if (observed != null) return rejected(request, observed)
            val text =
                try {
                    output.decodeToString(throwOnInvalidSequence = true)
                } catch (_: CharacterCodingException) {
                    return rejected(request, PublicJqFailure.OUTPUT_INVALID)
                }
            return Refinement.Refined(PublicJqCapture(child.exitValue(), text))
        } catch (_: TimeoutException) {
            return rejected(request, PublicJqFailure.TIME_LIMIT)
        } finally {
            settleJqChild(child, workers)
        }
    }

    private fun read(
        input: InputStream,
        limit: Int,
        child: Process,
        failure: AtomicReference<PublicJqFailure?>,
        limitFailure: PublicJqFailure,
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        try {
            var count = input.read(buffer)
            while (count >= 0) {
                if (count > limit - bytes.size()) {
                    failure.set(limitFailure)
                    child.destroyForcibly()
                    return bytes.toByteArray()
                }
                bytes.write(buffer, 0, count)
                count = input.read(buffer)
            }
        } catch (_: IOException) {
            failure.compareAndSet(null, PublicJqFailure.PROCESS_IO)
            child.destroyForcibly()
        }
        return bytes.toByteArray()
    }
}

private fun remaining(deadline: Long): Long = (deadline - System.nanoTime()).coerceAtLeast(1)

private fun <Value> await(future: Future<Value>, deadline: Long): Value =
    try {
        future.get(remaining(deadline), TimeUnit.NANOSECONDS)
    } catch (failure: ExecutionException) {
        throw IllegalStateException("jq pipe worker defect", failure)
    }

@Suppress("SwallowedException") // Restore interruption after the owned child settles, rather than abandoning it.
private fun reap(child: Process) {
    var interrupted = false
    while (true) {
        try {
            child.waitFor()
            break
        } catch (_: InterruptedException) {
            interrupted = true
        }
    }
    if (interrupted) Thread.currentThread().interrupt()
}

private fun settleJqChild(child: Process, workers: ExecutorService) {
    if (child.isAlive) child.destroyForcibly()
    reap(child)
    workers.shutdownNow()
    // The child has settled; stream-close errors cannot replace the selected process outcome.
    for (stream in listOf(child.outputStream, child.inputStream, child.errorStream)) {
        try {
            stream.close()
        } catch (_: IOException) {
            /* settled child */
        }
    }
    var interrupted = false
    while (!workers.isTerminated) {
        try {
            workers.awaitTermination(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            interrupted = true
        }
    }
    if (interrupted) Thread.currentThread().interrupt()
}

private fun rejected(
    request: PublicJqProcessRequest,
    failure: PublicJqFailure,
): Refinement.Rejected<PublicJqRejection> = Refinement.Rejected(jqRejection(failure, request.stage, request.limits))
