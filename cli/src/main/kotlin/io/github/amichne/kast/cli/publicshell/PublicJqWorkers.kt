package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.kernel.Refinement
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

internal data class PublicJqCapture(val childExit: Int, val stdout: String)

/** One bounded stage of an admitted program. [program] is the encoded worker argument. */
internal data class PublicJqProcessRequest(
    val stage: PublicJqStage,
    val program: String,
    val input: String,
    val limits: PublicJqLimits,
)

/** Effect seam: observations are captured here; engine admission and result policy remain in PublicJqEngine. */
internal fun interface PublicJqExecutor {
    fun execute(request: PublicJqProcessRequest): Refinement<PublicJqCapture, PublicJqRejection>
}

/**
 * Warm jq workers, each in its own JVM with a bounded heap. An embedded evaluation cannot be interrupted, so a request
 * that exceeds its deadline or a limit, or whose caller is cancelled, kills its worker; the next request starts
 * another. At most [capacity] workers exist, and a request waits for one within its own deadline.
 */
internal class PublicJqWorkers(
    private val start: (ProcessBuilder) -> Process = ProcessBuilder::start,
    private val capacity: Int = DEFAULT_CAPACITY,
) : PublicJqExecutor, AutoCloseable {
    private val permits = Semaphore(capacity)
    private val idle = ArrayDeque<JqWorker>()

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
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(request.limits.timeoutMillis)
        if (!permits.tryAcquire(remaining(deadline), TimeUnit.NANOSECONDS))
            return rejected(request, PublicJqFailure.TIME_LIMIT)
        try {
            val worker =
                synchronized(idle) { idle.removeFirstOrNull() }
                    ?: when (val started = spawn()) {
                        is Refinement.Rejected -> return rejected(request, started.failure)
                        is Refinement.Refined -> started.value
                    }
            var kept = false
            try {
                val outcome =
                    worker.exchange(
                        JqRequest(request.stage, request.limits.bytes, request.program, bytes),
                        request.limits.bytes,
                        deadline,
                    )
                kept = outcome.reusable
                return when (val result = outcome.result) {
                    is Refinement.Rejected -> rejected(request, result.failure)
                    is Refinement.Refined -> result
                }
            } finally {
                if (kept) synchronized(idle) { idle.addLast(worker) } else worker.stop()
            }
        } finally {
            permits.release()
        }
    }

    private fun spawn(): Refinement<JqWorker, PublicJqFailure> {
        val command =
            when (val resolved = PublicJqWorkerCommand.current()) {
                is Refinement.Rejected -> return resolved
                is Refinement.Refined -> resolved.value.arguments
            }
        return try {
            // The embedded evaluator reads no files or environment, so the worker gets neither.
            val process =
                start(ProcessBuilder(command).directory(Path.of("/").toFile()).apply { environment().clear() })
            Refinement.Refined(JqWorker(process))
        } catch (_: IOException) {
            Refinement.Rejected(PublicJqFailure.ENGINE_UNAVAILABLE)
        } catch (_: SecurityException) {
            Refinement.Rejected(PublicJqFailure.ENGINE_UNAVAILABLE)
        }
    }

    override fun close() {
        synchronized(idle) {
            idle.forEach(JqWorker::stop)
            idle.clear()
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 2

        /** The workers this process owns. They end with it, because their standard input closes. */
        val process: PublicJqWorkers by lazy { PublicJqWorkers() }
    }
}

private class JqOutcome(val result: Refinement<PublicJqCapture, PublicJqFailure>, val reusable: Boolean)

/** One worker process, its request stream, and a drain that bounds its diagnostics without publishing them. */
private class JqWorker(private val process: Process) {
    private val stderr = AtomicLong()
    private val threads: ExecutorService =
        Executors.newFixedThreadPool(2) { action -> Thread(action, "kast-jq-worker").apply { isDaemon = true } }
    private val requests = DataOutputStream(process.outputStream.buffered())
    private val replies = DataInputStream(process.inputStream.buffered())

    init {
        threads.execute { drain(process.errorStream) }
    }

    fun exchange(request: JqRequest, limit: Int, deadline: Long): JqOutcome {
        val reply =
            threads.submit<Pair<Int, ByteArray>?> {
                JqFrames.writeRequest(requests, request)
                JqFrames.readReply(replies, limit)
            }
        val observed =
            try {
                reply.get(remaining(deadline), TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                return JqOutcome(Refinement.Rejected(PublicJqFailure.TIME_LIMIT), reusable = false)
            } catch (failure: ExecutionException) {
                if (failure.cause !is IOException) throw IllegalStateException("jq worker exchange failed", failure)
                val reason =
                    if (stderr.get() > PublicJqLimits.STDERR_BYTES) PublicJqFailure.STDERR_LIMIT
                    else PublicJqFailure.PROCESS_IO
                return JqOutcome(Refinement.Rejected(reason), reusable = false)
            } ?: return JqOutcome(Refinement.Rejected(PublicJqFailure.BYTE_LIMIT), reusable = false)
        if (stderr.get() > PublicJqLimits.STDERR_BYTES)
            return JqOutcome(Refinement.Rejected(PublicJqFailure.STDERR_LIMIT), reusable = false)
        val (status, output) = observed
        val text =
            try {
                output.decodeToString(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                return JqOutcome(Refinement.Rejected(PublicJqFailure.OUTPUT_INVALID), reusable = false)
            }
        // A worker that rejected a frame or exhausted its heap exits after replying.
        val reusable =
            status != PublicJqWorkerExit.INVALID_REQUEST.code && status != PublicJqWorkerExit.RESOURCE_LIMIT.code
        return JqOutcome(Refinement.Refined(PublicJqCapture(status, text)), reusable)
    }

    private fun drain(input: InputStream) {
        val buffer = ByteArray(BUFFER_BYTES)
        try {
            var count = input.read(buffer)
            while (count >= 0) {
                if (stderr.addAndGet(count.toLong()) > PublicJqLimits.STDERR_BYTES) {
                    process.destroyForcibly()
                    return
                }
                count = input.read(buffer)
            }
        } catch (_: IOException) {
            /* the worker has ended */
        }
    }

    @Suppress("SwallowedException") // Restore interruption after the owned child settles, rather than abandoning it.
    fun stop() {
        if (process.isAlive) process.destroyForcibly()
        var interrupted = false
        while (true) {
            try {
                process.waitFor()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        threads.shutdownNow()
        for (stream in listOf(process.outputStream, process.inputStream, process.errorStream)) {
            try {
                stream.close()
            } catch (_: IOException) {
                /* settled child */
            }
        }
        while (!threads.isTerminated) {
            try {
                threads.awaitTermination(1, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}

private const val BUFFER_BYTES = 8192

private fun remaining(deadline: Long): Long = (deadline - System.nanoTime()).coerceAtLeast(1)

private fun rejected(
    request: PublicJqProcessRequest,
    failure: PublicJqFailure,
): Refinement.Rejected<PublicJqRejection> = Refinement.Rejected(jqRejection(failure, request.stage, request.limits))
