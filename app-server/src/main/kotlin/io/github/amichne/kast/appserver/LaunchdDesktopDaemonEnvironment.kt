package io.github.amichne.kast.appserver

import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Called by the existing GUI-domain service; this does not launch or modify ChatGPT. */
internal object LaunchdDesktopDaemonEnvironment : DesktopDaemonEnvironment {
    override fun readHome(): DesktopDaemonHomeRead =
        when (val result = run("getenv", Variable.HOME)) {
            is CommandResult.Rejected -> DesktopDaemonHomeRead.Rejected(result.failure)
            is CommandResult.Completed -> interpretHome(result.exitCode, result.output)
        }

    internal fun interpretHome(exitCode: Int, output: String): DesktopDaemonHomeRead {
        if (exitCode != 0) return DesktopDaemonHomeRead.Rejected(DesktopDiscoveryFailure.COMMAND_REJECTED)
        if (output.isEmpty()) return DesktopDaemonHomeRead.Default
        return try {
            val raw = output.removeSuffix("\n")
            val path = Path.of(raw)
            if (!output.endsWith("\n") || raw.contains('\n'))
                return DesktopDaemonHomeRead.Rejected(DesktopDiscoveryFailure.HOME_REJECTED)
            if (!path.isAbsolute || path.normalize() != path)
                DesktopDaemonHomeRead.Rejected(DesktopDiscoveryFailure.HOME_REJECTED)
            else DesktopDaemonHomeRead.Configured(CodexControlSocketPath.from(path))
        } catch (_: java.nio.file.InvalidPathException) {
            DesktopDaemonHomeRead.Rejected(DesktopDiscoveryFailure.HOME_REJECTED)
        }
    }

    override fun read(): DesktopDaemonEnvironmentRead =
        when (val result = run("getenv", Variable.FLAG)) {
            is CommandResult.Rejected -> DesktopDaemonEnvironmentRead.Rejected(result.failure)
            is CommandResult.Completed -> interpretRead(result.exitCode, result.output)
        }

    internal fun interpretRead(exitCode: Int, output: String): DesktopDaemonEnvironmentRead =
        when {
            exitCode != 0 -> DesktopDaemonEnvironmentRead.Rejected(DesktopDiscoveryFailure.COMMAND_REJECTED)
            output.isEmpty() -> DesktopDaemonEnvironmentRead.Observed(DesktopDaemonSetting.ABSENT)
            output == "1\n" -> DesktopDaemonEnvironmentRead.Observed(DesktopDaemonSetting.ENABLED)
            else -> DesktopDaemonEnvironmentRead.Observed(DesktopDaemonSetting.CONFLICTING)
        }

    override fun enable(): DesktopDiscoveryOutcome = mutation("setenv", "1")

    override fun remove(): DesktopDiscoveryOutcome = mutation("unsetenv")

    private fun mutation(operation: String, vararg values: String): DesktopDiscoveryOutcome =
        when (val result = run(operation, Variable.FLAG, *values)) {
            is CommandResult.Rejected -> DesktopDiscoveryOutcome.Rejected(result.failure)
            is CommandResult.Completed ->
                if (result.exitCode == 0 && result.output.isEmpty()) DesktopDiscoveryOutcome.Ready
                else DesktopDiscoveryOutcome.Rejected(DesktopDiscoveryFailure.COMMAND_REJECTED)
        }

    private fun run(operation: String, variable: Variable, vararg values: String): CommandResult {
        val process =
            try {
                ProcessBuilder(listOf("/bin/launchctl", operation, variable.wireName) + values)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            } catch (_: IOException) {
                return CommandResult.Rejected(DesktopDiscoveryFailure.COMMAND_REJECTED)
            } catch (_: SecurityException) {
                return CommandResult.Rejected(DesktopDiscoveryFailure.COMMAND_REJECTED)
            }
        return try {
            if (!process.waitFor(BrokerOperationalLimits.desktopInspection.value, TimeUnit.MILLISECONDS))
                CommandResult.Rejected(DesktopDiscoveryFailure.COMMAND_TIMED_OUT)
            else {
                val bytes =
                    process.inputStream.use { it.readNBytes(BrokerOperationalLimits.maximumDesktopInspectionBytes + 1) }
                if (bytes.size > BrokerOperationalLimits.maximumDesktopInspectionBytes)
                    CommandResult.Rejected(DesktopDiscoveryFailure.COMMAND_REJECTED)
                else CommandResult.Completed(process.exitValue(), bytes.toString(Charsets.UTF_8))
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            CommandResult.Rejected(DesktopDiscoveryFailure.INTERRUPTED)
        } catch (_: IOException) {
            CommandResult.Rejected(DesktopDiscoveryFailure.COMMAND_REJECTED)
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private enum class Variable(val wireName: String) {
        HOME("CODEX_HOME"),
        FLAG("CODEX_APP_SERVER_USE_LOCAL_DAEMON"),
    }

    private sealed interface CommandResult {
        data class Completed(val exitCode: Int, val output: String) : CommandResult

        data class Rejected(val failure: DesktopDiscoveryFailure) : CommandResult
    }
}
