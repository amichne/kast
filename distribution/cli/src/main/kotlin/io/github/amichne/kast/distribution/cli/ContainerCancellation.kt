package io.github.amichne.kast.distribution.cli

import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

/** This invocation can signal only its admitted container and unique exec ID. */
internal class ContainerCancellation(
    runner: List<String>,
    private val retained: ContainerPlan.RetainedContainer,
    private val execution: UUID,
    private val directory: Path,
    environment: Map<String, String>,
) {
    private val runner = runner.toList()
    private val environment = environment.toMap()

    fun cancel(signal: PublicQuerySignal, child: Process) {
        val arguments =
            runner + listOf("kill", "--exec-id", execution.toString(), "--signal", signal.signalName, retained.id)
        val effects = NativeContainerCancellation(arguments, directory, environment, child)
        val outcome = cancelContainerExecution(effects)
        System.err.println("kast container: stage=cancel outcome=${outcome.name}")
        if (outcome != ContainerCancellationOutcome.TRANSPORT_EXIT_OBSERVED)
            System.err.println("kast container: Guest exec completion is unproved.")
    }
}

internal enum class ContainerSignalAttempt {
    ACCEPTED,
    REJECTED,
    TRANSPORT_EXITED,
    START_FAILED,
    DEADLINE_EXCEEDED,
    INTERRUPTED,
}

internal enum class ContainerCancellationWait {
    EXITED,
    RUNNING,
    INTERRUPTED,
}

internal enum class ContainerCancellationOutcome {
    TRANSPORT_EXIT_OBSERVED,
    TRANSPORT_EXITED_BEFORE_SIGNAL,
    SIGNAL_START_FAILED,
    DEADLINE_EXCEEDED,
    RETRY_EXHAUSTED,
    EXECUTION_STILL_RUNNING,
    INTERRUPTED,
}

internal interface ContainerCancellationEffects {
    fun send(): ContainerSignalAttempt

    fun awaitRetry(): ContainerCancellationWait

    fun awaitExit(): ContainerCancellationWait
}

/** Nonzero signal commands can precede guest registration. Neither retries nor delivery prove descendant retirement. */
internal fun cancelContainerExecution(effects: ContainerCancellationEffects): ContainerCancellationOutcome {
    for (attempt in 1..MAXIMUM_SIGNAL_ATTEMPTS) {
        when (effects.send()) {
            ContainerSignalAttempt.ACCEPTED ->
                return when (effects.awaitExit()) {
                    ContainerCancellationWait.EXITED -> ContainerCancellationOutcome.TRANSPORT_EXIT_OBSERVED
                    ContainerCancellationWait.RUNNING -> ContainerCancellationOutcome.EXECUTION_STILL_RUNNING
                    ContainerCancellationWait.INTERRUPTED -> ContainerCancellationOutcome.INTERRUPTED
                }
            ContainerSignalAttempt.REJECTED -> {
                if (attempt == MAXIMUM_SIGNAL_ATTEMPTS) return ContainerCancellationOutcome.RETRY_EXHAUSTED
                when (effects.awaitRetry()) {
                    ContainerCancellationWait.EXITED ->
                        return ContainerCancellationOutcome.TRANSPORT_EXITED_BEFORE_SIGNAL
                    ContainerCancellationWait.RUNNING -> Unit
                    ContainerCancellationWait.INTERRUPTED -> return ContainerCancellationOutcome.INTERRUPTED
                }
            }
            ContainerSignalAttempt.TRANSPORT_EXITED ->
                return ContainerCancellationOutcome.TRANSPORT_EXITED_BEFORE_SIGNAL
            ContainerSignalAttempt.START_FAILED -> return ContainerCancellationOutcome.SIGNAL_START_FAILED
            ContainerSignalAttempt.DEADLINE_EXCEEDED -> return ContainerCancellationOutcome.DEADLINE_EXCEEDED
            ContainerSignalAttempt.INTERRUPTED -> return ContainerCancellationOutcome.INTERRUPTED
        }
    }
    error("Cancellation attempt limit did not return")
}

private const val MAXIMUM_SIGNAL_ATTEMPTS = 10
private const val CANCELLATION_ALLOWANCE_SECONDS = 5L
private const val SIGNAL_ALLOWANCE_SECONDS = 1L
private const val RETRY_DELAY_MILLIS = 100L

private class NativeContainerCancellation(
    private val arguments: List<String>,
    private val directory: Path,
    private val environment: Map<String, String>,
    private val child: Process,
) : ContainerCancellationEffects {
    private val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CANCELLATION_ALLOWANCE_SECONDS)

    override fun send(): ContainerSignalAttempt {
        if (!child.isAlive) return ContainerSignalAttempt.TRANSPORT_EXITED
        val allowance = minOf(remaining(), TimeUnit.SECONDS.toNanos(SIGNAL_ALLOWANCE_SECONDS))
        if (allowance <= 0) return ContainerSignalAttempt.DEADLINE_EXCEEDED
        val sender =
            try {
                ProcessBuilder(arguments)
                    .directory(directory.toFile())
                    .apply {
                        environment().clear()
                        environment().putAll(environment)
                    }
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            } catch (_: IOException) {
                return ContainerSignalAttempt.START_FAILED
            }
        return try {
            sender.outputStream.close()
            if (!sender.waitFor(allowance, TimeUnit.NANOSECONDS)) ContainerSignalAttempt.DEADLINE_EXCEEDED
            else if (sender.exitValue() == 0) ContainerSignalAttempt.ACCEPTED else ContainerSignalAttempt.REJECTED
        } catch (_: IOException) {
            ContainerSignalAttempt.START_FAILED
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            ContainerSignalAttempt.INTERRUPTED
        } finally {
            if (sender.isAlive) {
                sender.destroyForcibly()
                try {
                    sender.waitFor(remaining().coerceAtLeast(0), TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    override fun awaitRetry(): ContainerCancellationWait =
        await(minOf(remaining(), TimeUnit.MILLISECONDS.toNanos(RETRY_DELAY_MILLIS)))

    override fun awaitExit(): ContainerCancellationWait = await(remaining())

    private fun await(allowance: Long): ContainerCancellationWait =
        try {
            if (child.waitFor(allowance.coerceAtLeast(0), TimeUnit.NANOSECONDS)) ContainerCancellationWait.EXITED
            else ContainerCancellationWait.RUNNING
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            ContainerCancellationWait.INTERRUPTED
        }

    private fun remaining(): Long = deadline - System.nanoTime()
}
