package io.github.amichne.kast.cli

import java.io.IOException
import java.util.concurrent.TimeUnit

/** Direct process-effect adapter used when no external service manager is admitted. */
internal object JdkRuntimeProcessStarter : RuntimeProcessStarter {
    /**
     * Proof transition: `IndexerLaunchCommand -> RuntimeProcessStart`.
     *
     * Starts the exact admitted command with only Kast's allowlisted runtime environment. The
     * returned session retains the concrete child identity until endpoint readiness or terminal
     * startup, while process and environment failures remain finite [RuntimeProcessStart] data.
     */
    override fun start(command: IndexerLaunchCommand): RuntimeProcessStart {
        val environment = when (
            val resolution = MacOsRuntimeProcessEnvironment.resolve(command.runtime)
        ) {
            is MacOsRuntimeProcessEnvironmentResolution.Resolved -> resolution.environment
            is MacOsRuntimeProcessEnvironmentResolution.Rejected ->
                return RuntimeProcessStart.Rejected(
                    when (resolution.failure) {
                        MacOsRuntimeProcessEnvironmentFailure.JAVA_HOME_UNAVAILABLE ->
                            RuntimeProcessStartFailure.IdeaJbrUnavailable
                        MacOsRuntimeProcessEnvironmentFailure.USER_HOME_UNAVAILABLE ->
                            RuntimeProcessStartFailure.UserHomeUnavailable
                    },
                )
        }
        val launcher = try {
            ProcessBuilder(command.detachedArguments())
                .apply {
                    environment().clear()
                    environment().putAll(environment.variables)
                }
                .redirectError(ProcessBuilder.Redirect.appendTo(command.startupLog.toFile()))
                .start()
        } catch (_: IOException) {
            return RuntimeProcessStart.Rejected(
                RuntimeProcessStartFailure.ProcessCreationRejected,
            )
        } catch (_: SecurityException) {
            return RuntimeProcessStart.Rejected(
                RuntimeProcessStartFailure.ProcessCreationRejected,
            )
        }
        return when (val detachment = launcher.awaitDetachedChild()) {
            is DirectRuntimeChildStart.Started -> RuntimeProcessStart.Accepted(
                DirectRuntimeStartupSession(detachment.process),
                RuntimeProcessStartOrigin.STARTED,
            )
            DirectRuntimeChildStart.Interrupted -> RuntimeProcessStart.Interrupted
            DirectRuntimeChildStart.Rejected -> RuntimeProcessStart.Rejected(
                RuntimeProcessStartFailure.ChildStartRejected,
            )
        }
    }
}

/**
 * Boundary projection: `IndexerLaunchCommand -> List<String>`.
 *
 * Preserves the exact admitted indexer arguments while establishing a separate process group and
 * terminal-hangup immunity for the default direct child. The fixed shell program receives every
 * admitted argument positionally, so no workspace or runtime value is evaluated as shell source.
 * Raw arguments leave only at the [ProcessBuilder] boundary.
 */
private fun IndexerLaunchCommand.detachedArguments(): List<String> =
    listOf(
        SHELL_EXECUTABLE,
        "-c",
        DETACHED_LAUNCH_SCRIPT,
        SHELL_COMMAND_NAME,
        startupLog.toString(),
    ) + arguments

private sealed interface DirectRuntimeChildStart {
    data class Started(
        val process: ProcessHandle,
    ) : DirectRuntimeChildStart

    data object Interrupted : DirectRuntimeChildStart
    data object Rejected : DirectRuntimeChildStart
}

private sealed interface DirectRuntimeProcessIdAdmission {
    data class Admitted(
        val processId: DirectRuntimeProcessId,
    ) : DirectRuntimeProcessIdAdmission

    data object Rejected : DirectRuntimeProcessIdAdmission
}

/** Positive operating-system identity published by the fixed direct-launch shell boundary. */
@JvmInline
private value class DirectRuntimeProcessId private constructor(
    val value: Long,
) {
    fun resolve(): DirectRuntimeChildStart = try {
        val process = ProcessHandle.of(value).orElse(null)
        if (process != null && process.isAlive) {
            DirectRuntimeChildStart.Started(process)
        } else {
            DirectRuntimeChildStart.Rejected
        }
    } catch (_: SecurityException) {
        DirectRuntimeChildStart.Rejected
    }

    fun retire() {
        try {
            ProcessHandle.of(value).orElse(null)?.let { process ->
                if (process.isAlive) process.destroyForcibly()
            }
        } catch (_: SecurityException) {
        }
    }

    companion object {
        fun admit(raw: String?): DirectRuntimeProcessIdAdmission {
            val value = raw?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: return DirectRuntimeProcessIdAdmission.Rejected
            return DirectRuntimeProcessIdAdmission.Admitted(DirectRuntimeProcessId(value))
        }
    }
}

/**
 * Proof transition: `completed direct-launch shell -> DirectRuntimeChildStart`.
 *
 * Establishes the exact detached child identity only after the fixed shell boundary exits
 * successfully. Timeout, malformed identity, interruption, and inaccessible process state remain
 * closed finite outcomes.
 */
private fun Process.awaitDetachedChild(): DirectRuntimeChildStart {
    val completed = try {
        waitFor(DIRECT_LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (_: InterruptedException) {
        retireLaunchTree()
        Thread.currentThread().interrupt()
        return DirectRuntimeChildStart.Interrupted
    } catch (_: SecurityException) {
        retireLaunchTree()
        return DirectRuntimeChildStart.Rejected
    }
    if (!completed) {
        retireLaunchTree()
        return DirectRuntimeChildStart.Rejected
    }
    val rawProcessId = try {
        inputReader().use { it.readLine() }
    } catch (_: IOException) {
        return DirectRuntimeChildStart.Rejected
    }
    val processId = when (val admission = DirectRuntimeProcessId.admit(rawProcessId)) {
        is DirectRuntimeProcessIdAdmission.Admitted -> admission.processId
        DirectRuntimeProcessIdAdmission.Rejected -> return DirectRuntimeChildStart.Rejected
    }
    if (exitValue() != 0) {
        processId.retire()
        return DirectRuntimeChildStart.Rejected
    }
    return processId.resolve()
}

private fun Process.retireLaunchTree() {
    try {
        toHandle().descendants().use { descendants ->
            descendants.forEach { process ->
                if (process.isAlive) process.destroyForcibly()
            }
        }
        if (isAlive) destroyForcibly()
    } catch (_: SecurityException) {
    }
}

/** Startup-scoped proof that the exact directly started child remains alive. */
private class DirectRuntimeStartupSession(
    private val process: ProcessHandle,
) : AcceptedRuntimeStartupSession {
    override fun observe(): RuntimeSessionObservation = try {
        if (process.isAlive) {
            RuntimeSessionObservation.Present
        } else {
            RuntimeSessionObservation.Absent
        }
    } catch (_: SecurityException) {
        RuntimeSessionObservation.Rejected
    }
}

private const val SHELL_EXECUTABLE = "/bin/sh"
private const val SHELL_COMMAND_NAME = "kast-direct-sidecar"
private const val DIRECT_LAUNCH_TIMEOUT_SECONDS = 5L
private const val DETACHED_LAUNCH_SCRIPT =
    "startup_log=\"\$1\"\n" +
        "shift\n" +
        "set -m\n" +
        "/usr/bin/nohup \"\$@\" </dev/null >/dev/null 2>>\"\$startup_log\" &\n" +
        "printf '%s\\n' \"\$!\""
