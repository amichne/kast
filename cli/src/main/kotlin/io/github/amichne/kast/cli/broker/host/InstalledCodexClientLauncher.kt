package io.github.amichne.kast.cli.broker.host

import io.github.amichne.kast.cli.broker.BrokerServiceLaunchCommand
import io.github.amichne.kast.cli.broker.BrokerServiceLaunchCommandResolution
import io.github.amichne.kast.cli.broker.DesktopFacadeExecutable
import io.github.amichne.kast.cli.broker.UpstreamCodexExecutable
import io.github.amichne.kast.cli.broker.provider.BrokerExecutable
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

enum class CodexClientLaunch {
    Cli,
    Desktop,
}

enum class CodexClientLaunchFailure {
    FACADE_UNAVAILABLE,
    UPSTREAM_UNAVAILABLE,
    DESKTOP_UNAVAILABLE,
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
        val facade: DesktopFacadeExecutable,
    ) : CodexClientProcessRequest

    data class Desktop(
        val executable: CodexDesktopExecutable,
        val facade: DesktopFacadeExecutable,
        val upstream: UpstreamCodexExecutable,
    ) : CodexClientProcessRequest
}

internal fun interface CodexClientProcessLauncher {
    fun launch(request: CodexClientProcessRequest): CodexClientLaunchRun
}

fun interface CodexClientLauncher {
    fun launch(client: CodexClientLaunch): CodexClientLaunchRun
}

internal object UnavailableCodexClientLauncher : CodexClientLauncher {
    override fun launch(client: CodexClientLaunch): CodexClientLaunchRun =
        CodexClientLaunchRun.Rejected(CodexClientLaunchFailure.FACADE_UNAVAILABLE)
}

/** Resolves installed executables for one invocation and never mutates global user state. */
internal class InstalledCodexClientLauncher(
    private val kastExecutable: Path,
    private val userHome: Path,
    private val environment: Map<String, String> = System.getenv(),
    private val processLauncher: CodexClientProcessLauncher = JdkCodexClientProcessLauncher,
) : CodexClientLauncher {
    override fun launch(client: CodexClientLaunch): CodexClientLaunchRun {
        val facade = when (
            val admission = DesktopFacadeExecutable.admit(
                kastExecutable.parent.resolve("kast-codex"),
            )
        ) {
            is Refinement.Refined -> admission.value
            is Refinement.Rejected -> return CodexClientLaunchRun.Rejected(
                CodexClientLaunchFailure.FACADE_UNAVAILABLE,
            )
        }
        return when (client) {
            CodexClientLaunch.Cli -> processLauncher.launch(
                CodexClientProcessRequest.Cli(facade),
            )
            CodexClientLaunch.Desktop -> launchDesktop(facade)
        }
    }

    private fun launchDesktop(
        facade: DesktopFacadeExecutable,
    ): CodexClientLaunchRun {
        val upstream = when (
            val resolution = BrokerServiceLaunchCommand.resolve(
                kastExecutable,
                userHome,
                environment + ("CODEX_CLI_PATH" to facade.path.toString()),
            )
        ) {
            is BrokerServiceLaunchCommandResolution.Resolved -> resolution.command.codex
            is BrokerServiceLaunchCommandResolution.Rejected -> return CodexClientLaunchRun
                .Rejected(CodexClientLaunchFailure.UPSTREAM_UNAVAILABLE)
        }
        val executable = resolveDesktopExecutable()
            ?: return CodexClientLaunchRun.Rejected(
                CodexClientLaunchFailure.DESKTOP_UNAVAILABLE,
            )
        return processLauncher.launch(
            CodexClientProcessRequest.Desktop(executable, facade, upstream),
        )
    }

    private fun resolveDesktopExecutable(): CodexDesktopExecutable? {
        val explicit = environment[DESKTOP_EXECUTABLE_ENVIRONMENT]
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
            ProcessBuilder(request.facade.path.toString())
                .inheritIO()
                .start()
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
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(2, TimeUnit.SECONDS)
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
                builder.environment()["KAST_REAL_CODEX_EXECUTABLE"] =
                    request.upstream.path.toString()
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
