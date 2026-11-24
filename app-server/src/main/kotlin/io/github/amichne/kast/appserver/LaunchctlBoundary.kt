package io.github.amichne.kast.appserver

import java.io.IOException
import java.util.concurrent.TimeUnit

internal sealed interface LaunchctlInvocation {
    data object Completed : LaunchctlInvocation

    data object Absent : LaunchctlInvocation

    data object Rejected : LaunchctlInvocation

    data object Interrupted : LaunchctlInvocation

    data object TimedOut : LaunchctlInvocation
}

internal sealed interface LaunchctlExitContract {
    data object CompletionOnly : LaunchctlExitContract

    data class CompletionOrAbsent(val absentExitCode: Int) : LaunchctlExitContract
}

internal fun interface LaunchctlInvoker {
    /** Executes one already-assembled launchctl invocation at the operating-system boundary. */
    fun invoke(
        arguments: List<String>,
        exitContract: LaunchctlExitContract,
    ): LaunchctlInvocation
}

/**
 * Bounds the supervisor command. Submitted services run in the supervisor's own process scope. [configure] can replace
 * the discarded output of a command whose service inherits it.
 */
internal fun invokeServiceSupervisor(
    arguments: List<String>,
    exitContract: LaunchctlExitContract = LaunchctlExitContract.CompletionOnly,
    configure: ProcessBuilder.() -> Unit = {},
): LaunchctlInvocation {
    val process =
        try {
            ProcessBuilder(arguments)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .apply(configure)
                .start()
        } catch (_: IOException) {
            return LaunchctlInvocation.Rejected
        } catch (_: SecurityException) {
            return LaunchctlInvocation.Rejected
        }
    val completed =
        try {
            process.waitFor(BrokerOperationalLimits.launchctlInvocation.value, TimeUnit.MILLISECONDS)
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
        is LaunchctlExitContract.CompletionOrAbsent ->
            if (exitCode == exitContract.absentExitCode) {
                LaunchctlInvocation.Absent
            } else {
                LaunchctlInvocation.Rejected
            }
    }
}
