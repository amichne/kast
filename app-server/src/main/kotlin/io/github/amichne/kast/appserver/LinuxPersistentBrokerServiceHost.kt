package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement

internal class LinuxPersistentBrokerServiceHost(
    private val invoke: (List<String>, LaunchctlExitContract) -> LaunchctlInvocation = ::invokeServiceSupervisor
) : PersistentBrokerServiceHost {
    override fun ensure(command: BrokerServiceLaunchCommand): PersistentBrokerServiceAdmission =
        PersistentBrokerServiceAdmission.Rejected(PersistentBrokerServiceFailure.UNAVAILABLE)

    override fun submitSemantic(command: SemanticServiceLaunch): Refinement<Unit, PersistentBrokerServiceFailure> =
        BrokerServiceStartLock.submitSemantic(command) { submitExclusively(command) }

    private fun submitExclusively(command: SemanticServiceLaunch): Refinement<Unit, PersistentBrokerServiceFailure> {
        val unit = "${command.label}.service"
        val observation =
            invoke(
                listOf("/usr/bin/systemctl", "--user", "is-active", "--quiet", unit),
                LaunchctlExitContract.CompletionOnly,
            )
        val result =
            when (observation) {
                LaunchctlInvocation.Completed,
                LaunchctlInvocation.Interrupted,
                LaunchctlInvocation.TimedOut -> observation
                LaunchctlInvocation.Absent,
                LaunchctlInvocation.Rejected ->
                    invoke(unitArguments(command, unit), LaunchctlExitContract.CompletionOnly)
            }
        return when (result) {
            LaunchctlInvocation.Completed -> Refinement.Refined(Unit)
            LaunchctlInvocation.Interrupted -> Refinement.Rejected(PersistentBrokerServiceFailure.INTERRUPTED)
            LaunchctlInvocation.TimedOut -> Refinement.Rejected(PersistentBrokerServiceFailure.STARTUP_TIMED_OUT)
            LaunchctlInvocation.Absent,
            LaunchctlInvocation.Rejected ->
                Refinement.Rejected(PersistentBrokerServiceFailure.SERVICE_SUBMISSION_REJECTED)
        }
    }

    private fun unitArguments(command: SemanticServiceLaunch, unit: String): List<String> =
        listOf(
            "/usr/bin/systemd-run",
            "--user",
            "--quiet",
            "--collect",
            "--unit=$unit",
            "--property=Type=exec",
            "--property=WorkingDirectory=${command.installation.toString().replace("%", "%%")}",
            "--property=StandardInput=null",
            "--property=StandardOutput=journal",
            "--property=StandardError=journal",
            "--",
        ) + command.arguments.map { it.replace("\$", "\$\$") }
}
