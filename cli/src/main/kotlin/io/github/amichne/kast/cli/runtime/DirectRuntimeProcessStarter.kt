package io.github.amichne.kast.cli

import java.io.IOException

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
        val environment = when (val resolution = MacOsRuntimeProcessEnvironment.resolve()) {
            is MacOsRuntimeProcessEnvironmentResolution.Resolved -> resolution.environment
            is MacOsRuntimeProcessEnvironmentResolution.Rejected ->
                return RuntimeProcessStart.Rejected
        }
        val process = try {
            ProcessBuilder(command.arguments)
                .apply {
                    environment().clear()
                    environment().putAll(environment.variables)
                }
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (_: IOException) {
            return RuntimeProcessStart.Rejected
        } catch (_: SecurityException) {
            return RuntimeProcessStart.Rejected
        }
        return RuntimeProcessStart.Accepted(
            DirectRuntimeStartupSession(process.toHandle()),
            RuntimeProcessStartOrigin.STARTED,
        )
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
