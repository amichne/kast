package io.github.amichne.kast.appserver

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
