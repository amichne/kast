package io.github.amichne.kast.cli.broker.provider

import io.github.amichne.kast.cli.broker.core.CanonicalBrokerDirectory
import io.github.amichne.kast.cli.broker.core.ProviderFailureCode
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal enum class BrokerExecutableFailure { UNAVAILABLE }

@JvmInline
internal value class BrokerExecutable private constructor(
    val path: Path,
) {
    companion object {
        internal fun admit(candidate: Path): Refinement<BrokerExecutable, BrokerExecutableFailure> =
            try {
                val canonical = candidate.toRealPath()
                if (
                    Files.isRegularFile(canonical, LinkOption.NOFOLLOW_LINKS) &&
                    Files.isExecutable(canonical)
                ) {
                    Refinement.Refined(BrokerExecutable(canonical))
                } else {
                    Refinement.Rejected(BrokerExecutableFailure.UNAVAILABLE)
                }
            } catch (_: IOException) {
                Refinement.Rejected(BrokerExecutableFailure.UNAVAILABLE)
            } catch (_: SecurityException) {
                Refinement.Rejected(BrokerExecutableFailure.UNAVAILABLE)
            }
    }
}

internal enum class BrokerProcessRequestFailure {
    INVALID_OUTPUT_BUDGET,
    INVALID_TIMEOUT,
}

internal class BrokerProcessRequest private constructor(
    val executable: BrokerExecutable,
    val arguments: List<String>,
    val workingDirectory: CanonicalBrokerDirectory,
    val maximumOutputBytes: Int,
    val timeoutMillis: Long,
    val environment: Map<String, String> = emptyMap(),
) {
    companion object {
        internal fun admit(
            executable: BrokerExecutable,
            arguments: List<String>,
            workingDirectory: CanonicalBrokerDirectory,
            maximumOutputBytes: Int,
            timeoutMillis: Long,
            environment: Map<String, String> = emptyMap(),
        ): Refinement<BrokerProcessRequest, BrokerProcessRequestFailure> = when {
            maximumOutputBytes !in 1..MAXIMUM_OUTPUT_BYTES -> Refinement.Rejected(
                BrokerProcessRequestFailure.INVALID_OUTPUT_BUDGET,
            )
            timeoutMillis !in 1..MAXIMUM_TIMEOUT_MILLIS -> Refinement.Rejected(
                BrokerProcessRequestFailure.INVALID_TIMEOUT,
            )
            else -> Refinement.Refined(
                BrokerProcessRequest(
                    executable,
                    arguments,
                    workingDirectory,
                    maximumOutputBytes,
                    timeoutMillis,
                    environment,
                ),
            )
        }

        private const val MAXIMUM_OUTPUT_BYTES = 64 * 1_024 * 1_024
        private val MAXIMUM_TIMEOUT_MILLIS = OperationExecutionBudget.GRAPH_BUILD.invocation.value
    }
}

internal enum class BrokerProcessFailure {
    IO_REJECTED,
    OUTPUT_LIMIT,
    SPAWN_FAILED,
    TERMINATED,
    TIMED_OUT,
}

internal fun BrokerProcessFailure.providerFailureCode(): ProviderFailureCode = when (this) {
    BrokerProcessFailure.IO_REJECTED -> ProviderFailureCode.IO_REJECTED
    BrokerProcessFailure.OUTPUT_LIMIT -> ProviderFailureCode.OUTPUT_LIMIT
    BrokerProcessFailure.SPAWN_FAILED -> ProviderFailureCode.SPAWN_FAILED
    BrokerProcessFailure.TERMINATED -> ProviderFailureCode.TERMINATED
    BrokerProcessFailure.TIMED_OUT -> ProviderFailureCode.TIMED_OUT
}

internal sealed interface BrokerProcessExecution {
    data class Completed(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) : BrokerProcessExecution

    data class Rejected(
        val failure: BrokerProcessFailure,
    ) : BrokerProcessExecution
}

internal fun interface BrokerProcessExecutor {
    suspend fun execute(request: BrokerProcessRequest): BrokerProcessExecution
}

/** Shell-free bounded process adapter shared by the built-in Gradle and Kast providers. */
internal object JdkBrokerProcessExecutor : BrokerProcessExecutor {
    override suspend fun execute(request: BrokerProcessRequest): BrokerProcessExecution =
        withContext(Dispatchers.IO) {
            val process = try {
                ProcessBuilder(
                    listOf(request.executable.path.toString()) + request.arguments,
                )
                    .directory(request.workingDirectory.path.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(NULL_DEVICE.toFile()))
                    .also { builder -> builder.environment().putAll(request.environment) }
                    .start()
            } catch (_: IOException) {
                return@withContext BrokerProcessExecution.Rejected(
                    BrokerProcessFailure.SPAWN_FAILED,
                )
            } catch (_: SecurityException) {
                return@withContext BrokerProcessExecution.Rejected(
                    BrokerProcessFailure.SPAWN_FAILED,
                )
            }

            val processIo = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                withTimeout(request.timeoutMillis) {
                    val observedBytes = AtomicLong(0)
                    val stdout = processIo.async {
                        process.inputStream.use { input ->
                            readBounded(input, observedBytes, request.maximumOutputBytes, process)
                        }
                    }
                    val stderr = processIo.async {
                        process.errorStream.use { input ->
                            readBounded(input, observedBytes, request.maximumOutputBytes, process)
                        }
                    }
                    val exit = processIo.async { process.waitFor() }
                    val stdoutRead = stdout.await()
                    val stderrRead = stderr.await()
                    val exitCode = exit.await()
                    if (stdoutRead is BoundedRead.Exceeded || stderrRead is BoundedRead.Exceeded) {
                        BrokerProcessExecution.Rejected(BrokerProcessFailure.OUTPUT_LIMIT)
                    } else if (stdoutRead is BoundedRead.Rejected || stderrRead is BoundedRead.Rejected) {
                        BrokerProcessExecution.Rejected(BrokerProcessFailure.IO_REJECTED)
                    } else {
                        BrokerProcessExecution.Completed(
                            exitCode,
                            (stdoutRead as BoundedRead.Read).bytes.toString(Charsets.UTF_8),
                            (stderrRead as BoundedRead.Read).bytes.toString(Charsets.UTF_8),
                        )
                    }
                }
            } catch (_: TimeoutCancellationException) {
                terminate(process)
                BrokerProcessExecution.Rejected(BrokerProcessFailure.TIMED_OUT)
            } catch (cancelled: CancellationException) {
                terminate(process)
                throw cancelled
            } catch (_: InterruptedException) {
                terminate(process)
                Thread.currentThread().interrupt()
                BrokerProcessExecution.Rejected(BrokerProcessFailure.TERMINATED)
            } finally {
                if (process.isAlive) terminate(process)
                closeProcessStreams(process)
                processIo.cancel()
            }
        }

    private fun readBounded(
        input: java.io.InputStream,
        observedBytes: AtomicLong,
        maximumOutputBytes: Int,
        process: Process,
    ): BoundedRead {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        return try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (observedBytes.addAndGet(count.toLong()) > maximumOutputBytes) {
                    terminate(process)
                    return BoundedRead.Exceeded
                }
                output.write(buffer, 0, count)
            }
            BoundedRead.Read(output.toByteArray())
        } catch (_: IOException) {
            terminate(process)
            if (observedBytes.get() > maximumOutputBytes) BoundedRead.Exceeded
            else BoundedRead.Rejected
        }
    }

    private fun terminate(process: Process) {
        synchronized(process) {
            if (process.isAlive) process.destroy()
            closeProcessStreams(process)
            try {
                if (process.isAlive && !process.waitFor(250, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(250, TimeUnit.MILLISECONDS)
                }
            } catch (_: InterruptedException) {
                process.destroyForcibly()
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun closeProcessStreams(process: Process) {
        try {
            process.inputStream.close()
        } catch (_: IOException) {
            // The stream is already closed or unusable.
        }
        try {
            process.errorStream.close()
        } catch (_: IOException) {
            // The stream is already closed or unusable.
        }
        try {
            process.outputStream.close()
        } catch (_: IOException) {
            // The stream is already closed or unusable.
        }
    }

    private sealed interface BoundedRead {
        data class Read(val bytes: ByteArray) : BoundedRead
        data object Exceeded : BoundedRead
        data object Rejected : BoundedRead
    }

    private val NULL_DEVICE: Path = Path.of("/dev/null")
}
