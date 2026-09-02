package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapAttemptId
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

internal fun interface RuntimeProcessStarter {
    /** Executes only an already admitted [IndexerLaunchCommand]. */
    fun start(command: IndexerLaunchCommand): RuntimeProcessStart
}

internal sealed interface RuntimeProcessStart {
    /** A newly submitted child whose bootstrap attempt is exactly the command attempt. */
    data class Started(
        val session: AcceptedRuntimeStartupSession,
        val attemptId: SemanticRuntimeBootstrapAttemptId,
    ) : RuntimeProcessStart

    /** A pre-existing service whose child attempt cannot be recovered from launchd ownership. */
    data class ExistingSession(
        val session: AcceptedRuntimeStartupSession,
    ) : RuntimeProcessStart

    data class Rejected(val failure: RuntimeProcessStartFailure) : RuntimeProcessStart
    data object Interrupted : RuntimeProcessStart
}

sealed interface RuntimeProcessStartFailure {
    data object IdeaJbrUnavailable : RuntimeProcessStartFailure
    data object UserHomeUnavailable : RuntimeProcessStartFailure
    data object SessionObservationRejected : RuntimeProcessStartFailure
    data object SessionSubmissionRejected : RuntimeProcessStartFailure
    data object ProcessCreationRejected : RuntimeProcessStartFailure
    data object ChildStartRejected : RuntimeProcessStartFailure
}

/** launchd process-effect adapter retained behind the explicit installed opt-in. */
internal object LaunchdRuntimeProcessStarter : RuntimeProcessStarter {
    override fun start(command: IndexerLaunchCommand): RuntimeProcessStart =
        command.processSession.start(command)
}

/** One launchd service capability derived from an exact admitted runtime endpoint. */
internal class MacOsRuntimeProcessSession private constructor(
    private val serviceLabel: String,
    private val launchctl: LaunchctlInvoker,
) : RuntimeProcessSession {
    /**
     * Proof transition: `MacOsRuntimeProcessSession + IndexerLaunchCommand ->
     * RuntimeProcessStart`.
     *
     * Establishes that launchd accepted the exact indexer command or already owns its deterministic
     * user-session service, whose lifecycle is independent of the initiating CLI.
     * [RuntimeProcessStart] closes existing ownership, launch rejection, and interruption. Raw
     * labels and arguments leave only at the launchctl boundary.
     */
    fun start(command: IndexerLaunchCommand): RuntimeProcessStart {
        when (observe()) {
            RuntimeSessionObservation.Present -> return RuntimeProcessStart.ExistingSession(this)
            RuntimeSessionObservation.Interrupted -> return RuntimeProcessStart.Interrupted
            RuntimeSessionObservation.Rejected -> return RuntimeProcessStart.Rejected(
                RuntimeProcessStartFailure.SessionObservationRejected,
            )
            RuntimeSessionObservation.Absent -> Unit
        }
        val submission = when (val construction = launchctlSubmission(command)) {
            is MacOsLaunchctlSubmission.Ready -> construction.arguments
            is MacOsLaunchctlSubmission.Rejected -> return RuntimeProcessStart.Rejected(
                construction.failure.toProcessStartFailure(),
            )
        }
        return when (
            launchctl.invoke(submission, LaunchctlExitContract.CompletionOnly)
        ) {
            LaunchctlInvocation.Completed -> RuntimeProcessStart.Started(
                this,
                command.bootstrapAttemptId,
            )
            LaunchctlInvocation.Interrupted -> RuntimeProcessStart.Interrupted
            LaunchctlInvocation.Absent -> RuntimeProcessStart.Rejected(
                RuntimeProcessStartFailure.SessionSubmissionRejected,
            )
            LaunchctlInvocation.TimedOut -> RuntimeProcessStart.Rejected(
                RuntimeProcessStartFailure.SessionSubmissionRejected,
            )
            LaunchctlInvocation.Rejected -> startAfterRejectedSubmission(
                LaunchctlInvocation.Rejected,
            )
        }
    }

    /**
     * Proof transition: `LaunchctlInvocation.Rejected + MacOsRuntimeProcessSession ->
     * RuntimeProcessStart`.
     *
     * Refines a duplicate-submission race to [RuntimeProcessStart.ExistingSession] only when a second
     * exact-label observation proves launchd ownership. All unproven and interrupted states remain
     * closed [RuntimeProcessStart] failures.
     */
    private fun startAfterRejectedSubmission(
        rejected: LaunchctlInvocation.Rejected,
    ): RuntimeProcessStart = when (rejected) {
        LaunchctlInvocation.Rejected -> when (observe()) {
            RuntimeSessionObservation.Present -> RuntimeProcessStart.ExistingSession(this)
            RuntimeSessionObservation.Absent,
            RuntimeSessionObservation.Rejected,
                -> RuntimeProcessStart.Rejected(
                    RuntimeProcessStartFailure.SessionSubmissionRejected,
                )
            RuntimeSessionObservation.Interrupted -> RuntimeProcessStart.Interrupted
        }
    }

    /**
     * Proof transition: `IndexerLaunchCommand -> MacOsLaunchctlSubmission`.
     *
     * Establishes the exact launchd submission plus its minimal admitted non-secret environment.
     * The submitted wrapper retires its own exact service after every child exit, so startup
     * failure is bounded and observable instead of acquiring crash-restart authority.
     * [MacOsRuntimeProcessEnvironmentFailure] is the closed expected failure. Raw launch
     * arguments leave only at [invokeLaunchctl].
     */
    private fun launchctlSubmission(command: IndexerLaunchCommand): MacOsLaunchctlSubmission {
        val environment = when (
            val resolution = MacOsRuntimeProcessEnvironment.resolve(command.runtime)
        ) {
            is MacOsRuntimeProcessEnvironmentResolution.Resolved -> resolution.environment
            is MacOsRuntimeProcessEnvironmentResolution.Rejected ->
                return MacOsLaunchctlSubmission.Rejected(resolution.failure)
        }
        return MacOsLaunchctlSubmission.Ready(
            listOf(
                LAUNCHCTL_EXECUTABLE,
                "submit",
                "-l",
                serviceLabel,
                "-o",
                NULL_DEVICE,
                "-e",
                command.startupLog.toString(),
                "--",
                SHELL_EXECUTABLE,
                "-c",
                TERMINAL_STARTUP_WRAPPER,
                SESSION_WRAPPER_NAME,
                ENV_EXECUTABLE,
                "-i",
            ) + environment.assignments + command.arguments,
        )
    }

    /**
     * Proof transition: `MacOsRuntimeProcessSession -> RuntimeSessionObservation`.
     *
     * Establishes whether launchd currently owns the endpoint-derived service label.
     * [RuntimeSessionObservation.Rejected] closes inaccessible service state. The raw label leaves
     * only at the launchctl boundary.
     */
    override fun observe(): RuntimeSessionObservation = when (
        val invocation = launchctl.invoke(
            listOf(LAUNCHCTL_EXECUTABLE, "list", serviceLabel),
            LaunchctlExitContract.CompletionOrAbsent(LAUNCHCTL_SERVICE_NOT_FOUND),
        )
    ) {
        LaunchctlInvocation.Completed -> RuntimeSessionObservation.Present
        LaunchctlInvocation.Absent -> RuntimeSessionObservation.Absent
        LaunchctlInvocation.Interrupted -> RuntimeSessionObservation.Interrupted
        LaunchctlInvocation.Rejected,
        LaunchctlInvocation.TimedOut,
            -> RuntimeSessionObservation.Rejected
    }

    /**
     * Proof transition: `RuntimeSessionObservation.Present -> RuntimeSessionRetirement`.
     *
     * Establishes that launchd accepted removal of the exact endpoint-derived service.
     * [RuntimeSessionRetirement] closes process rejection and interruption. The raw label leaves
     * only at the launchctl boundary.
     */
    override fun retire(
        present: RuntimeSessionObservation.Present,
    ): RuntimeSessionRetirement = when (present) {
        RuntimeSessionObservation.Present -> when (
            launchctl.invoke(
                listOf(LAUNCHCTL_EXECUTABLE, "remove", serviceLabel),
                LaunchctlExitContract.CompletionOnly,
            )
        ) {
            LaunchctlInvocation.Completed -> RuntimeSessionRetirement.Retired
            LaunchctlInvocation.Interrupted -> RuntimeSessionRetirement.Interrupted
        LaunchctlInvocation.Absent,
        LaunchctlInvocation.Rejected,
        LaunchctlInvocation.TimedOut,
            -> RuntimeSessionRetirement.Rejected
        }
    }

    companion object {
        /**
         * Proof transition: `RuntimeEndpoint -> MacOsRuntimeProcessSession`.
         *
         * Establishes one bounded launchd service identity derived from the exact canonical root,
         * runtime identity, and socket. Raw label extraction is permitted only by this launchctl
         * adapter.
         */
        fun from(endpoint: RuntimeEndpoint): MacOsRuntimeProcessSession =
            from(endpoint, JdkLaunchctlInvoker)

        /**
         * Proof transition: `RuntimeEndpoint + LaunchctlInvoker -> MacOsRuntimeProcessSession`.
         *
         * Establishes the same exact service identity while substituting only the outer launchctl
         * effect capability. Raw label extraction remains inside this adapter.
         */
        internal fun from(
            endpoint: RuntimeEndpoint,
            launchctl: LaunchctlInvoker,
        ): MacOsRuntimeProcessSession {
            val source = buildString {
                append(endpoint.root.path)
                append('\n')
                append(endpoint.runtimeId.value)
                append('\n')
                append(endpoint.socketPath)
            }
            val digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(source.toByteArray(StandardCharsets.UTF_8)),
                0,
                SERVICE_DIGEST_BYTES,
            )
            return MacOsRuntimeProcessSession("$SERVICE_LABEL_PREFIX.$digest", launchctl)
        }
    }
}

private fun MacOsRuntimeProcessEnvironmentFailure.toProcessStartFailure(): RuntimeProcessStartFailure =
    when (this) {
        MacOsRuntimeProcessEnvironmentFailure.JAVA_HOME_UNAVAILABLE ->
            RuntimeProcessStartFailure.IdeaJbrUnavailable
        MacOsRuntimeProcessEnvironmentFailure.USER_HOME_UNAVAILABLE ->
            RuntimeProcessStartFailure.UserHomeUnavailable
    }

private sealed interface MacOsLaunchctlSubmission {
    data class Ready(
        val arguments: List<String>,
    ) : MacOsLaunchctlSubmission

    data class Rejected(
        val failure: MacOsRuntimeProcessEnvironmentFailure,
    ) : MacOsLaunchctlSubmission
}

internal sealed interface RuntimeSessionObservation {
    data object Present : RuntimeSessionObservation
    data object Absent : RuntimeSessionObservation
    data object Rejected : RuntimeSessionObservation
    data object Interrupted : RuntimeSessionObservation
}

internal sealed interface RuntimeSessionRetirement {
    data object Retired : RuntimeSessionRetirement
    data object Rejected : RuntimeSessionRetirement
    data object Interrupted : RuntimeSessionRetirement
}

internal sealed interface LaunchctlInvocation {
    data object Completed : LaunchctlInvocation
    data object Absent : LaunchctlInvocation
    data object Rejected : LaunchctlInvocation
    data object Interrupted : LaunchctlInvocation
    data object TimedOut : LaunchctlInvocation
}

internal sealed interface LaunchctlExitContract {
    data object CompletionOnly : LaunchctlExitContract

    data class CompletionOrAbsent(
        val absentExitCode: Int,
    ) : LaunchctlExitContract
}

internal fun interface LaunchctlInvoker {
    /** Executes one already-assembled launchctl invocation at the operating-system boundary. */
    fun invoke(
        arguments: List<String>,
        exitContract: LaunchctlExitContract,
    ): LaunchctlInvocation
}

private object JdkLaunchctlInvoker : LaunchctlInvoker {
    override fun invoke(
        arguments: List<String>,
        exitContract: LaunchctlExitContract,
    ): LaunchctlInvocation = invokeLaunchctl(arguments, exitContract)
}

/**
 * Proof transition: `List<String> + LaunchctlExitContract -> LaunchctlInvocation`.
 *
 * Establishes launchctl's finite exit observation for one already assembled macOS command.
 * [LaunchctlInvocation] closes missing-service, process, security, and interruption states. Raw
 * arguments leave only at [ProcessBuilder].
 */
private fun invokeLaunchctl(
    arguments: List<String>,
    exitContract: LaunchctlExitContract,
): LaunchctlInvocation {
    if (System.getProperty("os.name") != MAC_OS_NAME) return LaunchctlInvocation.Rejected
    val process = try {
        ProcessBuilder(arguments)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    } catch (_: IOException) {
        return LaunchctlInvocation.Rejected
    } catch (_: SecurityException) {
        return LaunchctlInvocation.Rejected
    }
    val completed = try {
        process.waitFor(LAUNCHCTL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
    } catch (_: InterruptedException) {
        process.destroyForcibly()
        Thread.currentThread().interrupt()
        return LaunchctlInvocation.Interrupted
    } catch (_: SecurityException) {
        process.destroyForcibly()
        return LaunchctlInvocation.Rejected
    }
    if (!completed) {
        process.destroyForcibly()
        return LaunchctlInvocation.TimedOut
    }
    val exitCode = process.exitValue()
    if (exitCode == 0) return LaunchctlInvocation.Completed
    return when (exitContract) {
        LaunchctlExitContract.CompletionOnly -> LaunchctlInvocation.Rejected
        is LaunchctlExitContract.CompletionOrAbsent -> if (
            exitCode == exitContract.absentExitCode
        ) {
            LaunchctlInvocation.Absent
        } else {
            LaunchctlInvocation.Rejected
        }
    }
}

private const val LAUNCHCTL_EXECUTABLE = "/bin/launchctl"
private const val ENV_EXECUTABLE = "/usr/bin/env"
private const val SHELL_EXECUTABLE = "/bin/sh"
private const val NULL_DEVICE = "/dev/null"
private const val SESSION_WRAPPER_NAME = "kast-indexer-session"
private const val LAUNCHCTL_TIMEOUT_SECONDS = 5L
private const val TERMINAL_STARTUP_WRAPPER =
    "\"${'$'}@\"; status=${'$'}?; " +
        "/bin/launchctl remove \"${'$'}XPC_SERVICE_NAME\" >/dev/null 2>&1 || true; " +
        "exit \"${'$'}status\""
private const val MAC_OS_NAME = "Mac OS X"
private const val LAUNCHCTL_SERVICE_NOT_FOUND = 113
private const val SERVICE_DIGEST_BYTES = 12
private const val SERVICE_LABEL_PREFIX = "io.github.amichne.kast.indexer"
