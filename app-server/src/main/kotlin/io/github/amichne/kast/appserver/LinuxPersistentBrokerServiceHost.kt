package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Starts the semantic coordinator beneath the user's systemd manager, or detached when the user has none, as in most
 * containers. Only the start needs a supervisor; retirement uses the coordinator's socket and its process incarnation.
 */
internal class LinuxPersistentBrokerServiceHost(
    private val userRuntimeBase: Path = Path.of("/run/user"),
    private val detach: (SemanticServiceLaunch) -> LaunchctlInvocation = ::startDetached,
    private val observe: (SemanticServiceLaunch) -> SemanticPublicationState = {
        SemanticPublicationState.inspect(it.installation, it.readiness, it.executable)
    },
    private val pause: () -> Unit = { Thread.sleep(BrokerOperationalLimits.servicePoll.value) },
    private val invoke: (List<String>, LaunchctlExitContract) -> LaunchctlInvocation = { arguments, contract ->
        invokeServiceSupervisor(arguments, contract)
    },
) : PersistentBrokerServiceHost {
    override fun ensure(command: BrokerServiceLaunchCommand): PersistentBrokerServiceAdmission =
        PersistentBrokerServiceAdmission.Rejected(PersistentBrokerServiceFailure.UNAVAILABLE)

    override fun submitSemantic(command: SemanticServiceLaunch): Refinement<Unit, PersistentBrokerServiceFailure> =
        BrokerServiceStartLock.submitSemantic(command) { submitExclusively(command) }

    private fun submitExclusively(command: SemanticServiceLaunch): Refinement<Unit, PersistentBrokerServiceFailure> =
        when (val manager = userManager(command)) {
            is UserManager.Present -> submitUnit(command, manager.runtime)
            UserManager.Absent -> submitDetached(command)
            UserManager.Rejected -> Refinement.Rejected(PersistentBrokerServiceFailure.SERVICE_OBSERVATION_REJECTED)
        }

    private fun submitUnit(
        command: SemanticServiceLaunch,
        runtime: Path,
    ): Refinement<Unit, PersistentBrokerServiceFailure> {
        val environment = listOf("/usr/bin/env", "-i", "XDG_RUNTIME_DIR=$runtime")
        val unit = "${command.label}.service"
        val observation =
            invoke(
                environment + listOf("/usr/bin/systemctl", "--user", "is-active", "--quiet", unit),
                LaunchctlExitContract.CompletionOnly,
            )
        return when (observation) {
            LaunchctlInvocation.Completed,
            LaunchctlInvocation.Interrupted,
            LaunchctlInvocation.TimedOut -> observation
            LaunchctlInvocation.Absent,
            LaunchctlInvocation.Rejected ->
                invoke(environment + unitArguments(command, unit), LaunchctlExitContract.CompletionOnly)
        }.admission()
    }

    /**
     * No manager excludes a second start. So the caller's start lock stays held until this start publishes, and a
     * concurrent caller then observes that publication instead of an absent one.
     */
    private fun submitDetached(command: SemanticServiceLaunch): Refinement<Unit, PersistentBrokerServiceFailure> {
        val started = detach(command).admission()
        if (started is Refinement.Rejected) return started
        repeat(PUBLICATION_POLLS) {
            if (observe(command) != SemanticPublicationState.Missing) return started
            try {
                pause()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return Refinement.Rejected(PersistentBrokerServiceFailure.INTERRUPTED)
            }
        }
        return Refinement.Rejected(PersistentBrokerServiceFailure.STARTUP_TIMED_OUT)
    }

    /** A present runtime directory must be the user's own. It has a manager only while the manager's socket exists. */
    private fun userManager(command: SemanticServiceLaunch): UserManager {
        val uid = Files.getAttribute(command.home, "unix:uid", NOFOLLOW_LINKS) as? Int ?: return UserManager.Rejected
        val runtime = userRuntimeBase.resolve(uid.toString())
        if (Files.notExists(runtime, NOFOLLOW_LINKS)) return UserManager.Absent
        val owner = Files.getOwner(command.installation, NOFOLLOW_LINKS)
        val owned =
            Files.isDirectory(runtime, NOFOLLOW_LINKS) &&
                runtime.toRealPath() == runtime &&
                Files.getOwner(runtime, NOFOLLOW_LINKS) == owner &&
                Files.getPosixFilePermissions(runtime, NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rwx------")
        return when {
            !owned -> UserManager.Rejected
            Files.exists(runtime.resolve("systemd/private"), NOFOLLOW_LINKS) -> UserManager.Present(runtime)
            else -> UserManager.Absent
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

    private sealed interface UserManager {
        data class Present(val runtime: Path) : UserManager

        data object Absent : UserManager

        data object Rejected : UserManager
    }

    private companion object {
        val PUBLICATION_POLLS =
            (BrokerOperationalLimits.serviceStartup.value / BrokerOperationalLimits.servicePoll.value).toInt()
    }
}

/** A new session survives the caller's process group and terminal. Its output goes to the service's private log. */
internal fun startDetached(command: SemanticServiceLaunch): LaunchctlInvocation =
    invokeServiceSupervisor(
        listOf("/usr/bin/setsid", "--fork") + command.arguments,
        LaunchctlExitContract.CompletionOnly,
    ) {
        directory(command.installation.toFile())
        redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
        redirectOutput(ProcessBuilder.Redirect.appendTo(command.log.toFile()))
        redirectError(ProcessBuilder.Redirect.appendTo(command.log.toFile()))
    }

private fun LaunchctlInvocation.admission(): Refinement<Unit, PersistentBrokerServiceFailure> =
    when (this) {
        LaunchctlInvocation.Completed -> Refinement.Refined(Unit)
        LaunchctlInvocation.Interrupted -> Refinement.Rejected(PersistentBrokerServiceFailure.INTERRUPTED)
        LaunchctlInvocation.TimedOut -> Refinement.Rejected(PersistentBrokerServiceFailure.STARTUP_TIMED_OUT)
        LaunchctlInvocation.Absent,
        LaunchctlInvocation.Rejected -> Refinement.Rejected(PersistentBrokerServiceFailure.SERVICE_SUBMISSION_REJECTED)
    }
