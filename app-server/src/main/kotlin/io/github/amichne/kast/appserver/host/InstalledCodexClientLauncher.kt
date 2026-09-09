package io.github.amichne.kast.appserver.host

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationOwner
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration

import io.github.amichne.kast.appserver.BrokerServiceLaunchCommand
import io.github.amichne.kast.appserver.BrokerServiceLaunchCommandResolution
import io.github.amichne.kast.appserver.MacOsPersistentBrokerServiceHost
import io.github.amichne.kast.appserver.PersistentBrokerServiceAdmission
import io.github.amichne.kast.appserver.PersistentBrokerServiceFailure
import io.github.amichne.kast.appserver.PersistentBrokerServiceHost
import io.github.amichne.kast.appserver.host.admission.DesktopFacadeExecutable
import io.github.amichne.kast.appserver.host.admission.UpstreamCodexExecutable
import io.github.amichne.kast.appserver.provider.BrokerExecutable
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

enum class CodexClientLaunch {
    Cli,
    Desktop,
}

enum class CodexClientLaunchFailure {
    APP_SERVER_DISABLED,
    APP_SERVER_CONFIGURATION_REJECTED,
    APP_SERVER_UNAVAILABLE,
    DESKTOP_UNAVAILABLE,
    FACADE_UNAVAILABLE,
    DESKTOP_OVERRIDE_CONFLICT,
    PROCESS_REJECTED,
    INTERRUPTED,
}

internal enum class CodexDesktopExecutableFailure {
    UNAVAILABLE,
}

sealed interface CodexClientLaunchRun {
    data class Completed(val exitCode: Int) : CodexClientLaunchRun
    data class Rejected(val failure: CodexClientLaunchFailure) : CodexClientLaunchRun
}

@JvmInline
internal value class CodexDesktopExecutable private constructor(
    private val value: BrokerExecutable,
) {
    val path: Path get() = value.path

    companion object {
        internal fun admit(
            candidate: Path,
        ): Refinement<CodexDesktopExecutable, CodexDesktopExecutableFailure> =
            when (val admission = BrokerExecutable.admit(candidate)) {
                is Refinement.Refined -> Refinement.Refined(
                    CodexDesktopExecutable(admission.value),
                )
                is Refinement.Rejected -> Refinement.Rejected(
                    CodexDesktopExecutableFailure.UNAVAILABLE,
                )
            }
    }
}

internal sealed interface CodexClientProcessRequest {
    data class Cli(
        val upstream: UpstreamCodexExecutable,
        val publicSocket: Path,
        val codexHome: Path,
    ) : CodexClientProcessRequest

    data class Desktop(
        val executable: CodexDesktopExecutable,
        val facade: DesktopFacadeExecutable,
        val upstream: UpstreamCodexExecutable,
        val codexHome: Path,
    ) : CodexClientProcessRequest
}

internal fun interface CodexClientProcessLauncher {
    fun launch(request: CodexClientProcessRequest): CodexClientLaunchRun
}

fun interface CodexClientLauncher {
    fun launch(client: CodexClientLaunch): CodexClientLaunchRun
}

object UnavailableCodexClientLauncher : CodexClientLauncher {
    override fun launch(client: CodexClientLaunch): CodexClientLaunchRun =
        CodexClientLaunchRun.Rejected(CodexClientLaunchFailure.APP_SERVER_UNAVAILABLE)
}

/** Resolves and starts the persistent App Server before launching one standard Codex client. */
internal class InstalledCodexClientLauncher(
    private val kastExecutable: Path,
    private val userHome: Path,
    private val environment: Map<String, String> = System.getenv(),
    private val processLauncher: CodexClientProcessLauncher = JdkCodexClientProcessLauncher,
    private val serviceHost: PersistentBrokerServiceHost = MacOsPersistentBrokerServiceHost(),
) : CodexClientLauncher {
    override fun launch(client: CodexClientLaunch): CodexClientLaunchRun {
        if (client == CodexClientLaunch.Desktop &&
            (!environment["CODEX_CLI_PATH"].isNullOrEmpty() || environment["CODEX_APP_SERVER_FORCE_CLI"] == "1")) {
            return CodexClientLaunchRun.Rejected(CodexClientLaunchFailure.DESKTOP_OVERRIDE_CONFLICT)
        }
        val command = when (
            val resolution = BrokerServiceLaunchCommand.resolve(
                kastExecutable,
                userHome,
                environment,
            )
        ) {
            is BrokerServiceLaunchCommandResolution.Resolved -> resolution.command
            is BrokerServiceLaunchCommandResolution.Rejected -> return CodexClientLaunchRun
                .Rejected(resolution.failure.launchFailure())
        }
        val codex = when (val host = command.host) {
            is io.github.amichne.kast.appserver.BrokerHostSelection.Selected -> host.executable
            io.github.amichne.kast.appserver.BrokerHostSelection.Disabled,
            io.github.amichne.kast.appserver.BrokerHostSelection.NotConfigured -> return CodexClientLaunchRun.Rejected(CodexClientLaunchFailure.APP_SERVER_UNAVAILABLE)
        }
        val request = when (client) {
            CodexClientLaunch.Cli -> CodexClientProcessRequest.Cli(
                codex,
                command.publicSocket,
                command.codexHome,
            )
            CodexClientLaunch.Desktop -> when (val admission = desktopRequest(command, codex)) {
                is Refinement.Refined -> admission.value
                is Refinement.Rejected -> return CodexClientLaunchRun.Rejected(admission.failure)
            }
        }
        when (val admission = serviceHost.ensure(command)) {
            PersistentBrokerServiceAdmission.Ready -> Unit
            is PersistentBrokerServiceAdmission.Rejected -> return CodexClientLaunchRun.Rejected(
                admission.failure.launchFailure(),
            )
        }
        return processLauncher.launch(request)
    }

    private fun desktopRequest(
        command: BrokerServiceLaunchCommand,
        codex: io.github.amichne.kast.appserver.host.admission.UpstreamCodexExecutable,
    ): Refinement<CodexClientProcessRequest.Desktop, CodexClientLaunchFailure> {
        val facade = when (val admission = DesktopFacadeExecutable.admit(command.kast.parent.resolve("kast-codex"))) {
            is Refinement.Refined -> admission.value
            is Refinement.Rejected -> return Refinement.Rejected(CodexClientLaunchFailure.FACADE_UNAVAILABLE)
        }
        val executable = resolveDesktopExecutable(command.configuration)
            ?: return Refinement.Rejected(CodexClientLaunchFailure.DESKTOP_UNAVAILABLE)
        return Refinement.Refined(
            CodexClientProcessRequest.Desktop(executable, facade, codex, command.codexHome),
        )
    }

    private fun resolveDesktopExecutable(configuration: ResolvedKastConfiguration): CodexDesktopExecutable? {
        val explicit = configuration.ownerInputs(ConfigurationOwner.APP_SERVER)[DESKTOP_EXECUTABLE_ENVIRONMENT]
        if (explicit != null) {
            val candidate = try {
                Path.of(explicit).takeIf { path ->
                    path.isAbsolute && path.normalize() == path
                }
            } catch (_: RuntimeException) {
                null
            } ?: return null
            return (CodexDesktopExecutable.admit(candidate) as? Refinement.Refined)?.value
        }
        return listOf(
            Path.of("/Applications/Codex.app/Contents/MacOS/Codex"),
            Path.of("/Applications/ChatGPT.app/Contents/MacOS/ChatGPT"),
            userHome.resolve("Applications/Codex.app/Contents/MacOS/Codex"),
            userHome.resolve("Applications/ChatGPT.app/Contents/MacOS/ChatGPT"),
        ).firstNotNullOfOrNull { candidate ->
            (CodexDesktopExecutable.admit(candidate) as? Refinement.Refined)?.value
        }
    }

    private companion object {
        const val DESKTOP_EXECUTABLE_ENVIRONMENT = "KAST_CODEX_DESKTOP_EXECUTABLE"
    }
}

private object JdkCodexClientProcessLauncher : CodexClientProcessLauncher {
    override fun launch(request: CodexClientProcessRequest): CodexClientLaunchRun = when (request) {
        is CodexClientProcessRequest.Cli -> launchCli(request)
        is CodexClientProcessRequest.Desktop -> launchDesktop(request)
    }

    private fun launchCli(request: CodexClientProcessRequest.Cli): CodexClientLaunchRun {
        val process = try {
            ProcessBuilder(
                request.upstream.path.toString(),
                "--remote",
                "unix://${request.publicSocket}",
            ).inheritIO().also { builder ->
                builder.environment().remove("CODEX_CLI_PATH")
                builder.environment().remove("KAST_REAL_CODEX_EXECUTABLE")
                builder.environment()["CODEX_HOME"] = request.codexHome.toString()
            }.start()
        } catch (_: IOException) {
            return CodexClientLaunchRun.Rejected(CodexClientLaunchFailure.PROCESS_REJECTED)
        } catch (_: SecurityException) {
            return CodexClientLaunchRun.Rejected(CodexClientLaunchFailure.PROCESS_REJECTED)
        }
        return try {
            CodexClientLaunchRun.Completed(process.waitFor())
        } catch (_: InterruptedException) {
            process.destroy()
            try {
                if (!process.waitFor(BrokerOperationalLimits.clientProcessRetirementWait.value, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(BrokerOperationalLimits.clientProcessRetirementWait.value, TimeUnit.MILLISECONDS)
                }
            } catch (_: InterruptedException) {
                process.destroyForcibly()
            }
            Thread.currentThread().interrupt()
            CodexClientLaunchRun.Rejected(CodexClientLaunchFailure.INTERRUPTED)
        }
    }

    private fun launchDesktop(
        request: CodexClientProcessRequest.Desktop,
    ): CodexClientLaunchRun = try {
        ProcessBuilder(request.executable.path.toString())
            .redirectInput(ProcessBuilder.Redirect.from(NULL_DEVICE.toFile()))
            .redirectOutput(ProcessBuilder.Redirect.to(NULL_DEVICE.toFile()))
            .redirectError(ProcessBuilder.Redirect.to(NULL_DEVICE.toFile()))
            .also { builder ->
                builder.environment()["CODEX_CLI_PATH"] = request.facade.path.toString()
                builder.environment()["CODEX_EXECUTABLE"] = request.upstream.launcherPath.toString()
                builder.environment().remove("KAST_REAL_CODEX_EXECUTABLE")
                builder.environment()["CODEX_APP_SERVER_USE_LOCAL_DAEMON"] = "0"
                builder.environment()["CODEX_APP_SERVER_FORCE_CLI"] = "1"
                builder.environment()["CODEX_HOME"] = request.codexHome.toString()
            }
            .start()
        CodexClientLaunchRun.Completed(0)
    } catch (_: IOException) {
        CodexClientLaunchRun.Rejected(CodexClientLaunchFailure.PROCESS_REJECTED)
    } catch (_: SecurityException) {
        CodexClientLaunchRun.Rejected(CodexClientLaunchFailure.PROCESS_REJECTED)
    }

    private val NULL_DEVICE = Path.of("/dev/null")
}

private fun PersistentBrokerServiceFailure.launchFailure(): CodexClientLaunchFailure = when (this) {
    PersistentBrokerServiceFailure.DISABLED -> CodexClientLaunchFailure.APP_SERVER_DISABLED
    PersistentBrokerServiceFailure.CONFIGURATION_REJECTED,
    PersistentBrokerServiceFailure.KAST_EXECUTABLE_UNAVAILABLE,
    PersistentBrokerServiceFailure.CODEX_EXECUTABLE_UNAVAILABLE,
    PersistentBrokerServiceFailure.CODEX_HOME_REJECTED,
    PersistentBrokerServiceFailure.USER_HOME_REJECTED,
    PersistentBrokerServiceFailure.JAVA_RUNTIME_UNAVAILABLE,
    PersistentBrokerServiceFailure.SOCKET_PATH_REJECTED,
    PersistentBrokerServiceFailure.PROVIDER_CONFIGURATION_REJECTED,
    PersistentBrokerServiceFailure.PROTOCOL_CONFIGURATION_REJECTED,
        -> CodexClientLaunchFailure.APP_SERVER_CONFIGURATION_REJECTED
    PersistentBrokerServiceFailure.UNAVAILABLE,
    PersistentBrokerServiceFailure.KAST_QUALIFICATION_REJECTED,
    PersistentBrokerServiceFailure.CATALOG_REJECTED,
    PersistentBrokerServiceFailure.CODEX_QUALIFICATION_REJECTED,
    PersistentBrokerServiceFailure.THREAD_STORE_REJECTED,
    PersistentBrokerServiceFailure.UPSTREAM_REJECTED,
    PersistentBrokerServiceFailure.SERVER_REJECTED,
    PersistentBrokerServiceFailure.STATE_DIRECTORY_REJECTED,
    PersistentBrokerServiceFailure.SERVICE_LOCK_REJECTED,
    PersistentBrokerServiceFailure.SERVICE_OBSERVATION_REJECTED,
    PersistentBrokerServiceFailure.SERVICE_RETIREMENT_REJECTED,
    PersistentBrokerServiceFailure.SERVICE_SUBMISSION_REJECTED,
    PersistentBrokerServiceFailure.READINESS_REJECTED,
    PersistentBrokerServiceFailure.PUBLIC_SOCKET_OWNED,
    PersistentBrokerServiceFailure.SOCKET_PROBE_REJECTED,
    PersistentBrokerServiceFailure.LAUNCHCTL_TIMED_OUT,
    PersistentBrokerServiceFailure.STARTUP_TIMED_OUT,
        -> CodexClientLaunchFailure.APP_SERVER_UNAVAILABLE
    PersistentBrokerServiceFailure.INTERRUPTED -> CodexClientLaunchFailure.INTERRUPTED
}

fun installedCodexClientLauncher(kastExecutable: Path, userHome: Path): CodexClientLauncher =
    InstalledCodexClientLauncher(kastExecutable, userHome)
