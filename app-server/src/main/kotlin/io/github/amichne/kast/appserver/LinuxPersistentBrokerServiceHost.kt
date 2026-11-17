package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

internal class LinuxPersistentBrokerServiceHost(
    private val userRuntimeBase: Path = Path.of("/run/user"),
    private val invoke: (List<String>, LaunchctlExitContract) -> LaunchctlInvocation = ::invokeServiceSupervisor,
) : PersistentBrokerServiceHost {
    override fun ensure(command: BrokerServiceLaunchCommand): PersistentBrokerServiceAdmission =
        PersistentBrokerServiceAdmission.Rejected(PersistentBrokerServiceFailure.UNAVAILABLE)

    override fun submitSemantic(command: SemanticServiceLaunch): Refinement<Unit, PersistentBrokerServiceFailure> =
        BrokerServiceStartLock.submitSemantic(command) { submitExclusively(command) }

    private fun submitExclusively(command: SemanticServiceLaunch): Refinement<Unit, PersistentBrokerServiceFailure> {
        val runtime =
            ownedRuntimeDirectory(command)
                ?: return Refinement.Rejected(PersistentBrokerServiceFailure.SERVICE_OBSERVATION_REJECTED)
        val environment = listOf("/usr/bin/env", "-i", "XDG_RUNTIME_DIR=$runtime")
        val unit = "${command.label}.service"
        val observation =
            invoke(
                environment + listOf("/usr/bin/systemctl", "--user", "is-active", "--quiet", unit),
                LaunchctlExitContract.CompletionOnly,
            )
        val result =
            when (observation) {
                LaunchctlInvocation.Completed,
                LaunchctlInvocation.Interrupted,
                LaunchctlInvocation.TimedOut -> observation
                LaunchctlInvocation.Absent,
                LaunchctlInvocation.Rejected ->
                    invoke(environment + unitArguments(command, unit), LaunchctlExitContract.CompletionOnly)
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

    private fun ownedRuntimeDirectory(command: SemanticServiceLaunch): Path? {
        val uid = Files.getAttribute(command.home, "unix:uid", NOFOLLOW_LINKS) as? Int ?: return null
        val runtime = userRuntimeBase.resolve(uid.toString())
        val owner = Files.getOwner(command.installation, NOFOLLOW_LINKS)
        return runtime.takeIf {
            Files.isDirectory(it, NOFOLLOW_LINKS) &&
                it.toRealPath() == it &&
                Files.getOwner(it, NOFOLLOW_LINKS) == owner &&
                Files.getPosixFilePermissions(it, NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rwx------")
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
