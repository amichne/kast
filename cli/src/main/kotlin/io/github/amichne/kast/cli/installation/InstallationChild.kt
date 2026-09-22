package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.distribution.contract.configuration.InstallationOperationalLimits
import java.io.IOException
import java.time.Duration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal enum class InstallationChildStage {
    PRIOR_ADMISSION,
    PRIOR_RETIREMENT,
    PRIOR_REPLACEMENT,
    FORCE_RESET,
    CONFIGURATION_VALIDATION,
    CANDIDATE_QUALIFICATION,
    COMMAND_QUALIFICATION,
    APP_SERVER_ENABLE,
}

@Serializable
internal enum class InstallationChildOutcome {
    COMPLETED,
    EXIT_REJECTED,
    DEADLINE_EXCEEDED,
    IO_REJECTED,
    INTERRUPTED,
}

@Serializable
internal data class InstallationChildObservation(
    val event: String = "kast_installation",
    val stage: InstallationChildStage,
    val outcome: InstallationChildOutcome,
) {
    fun toJson(): String = Json { encodeDefaults = true }.encodeToString(serializer(), this)
}

/** The child retains admission authority; the parent records only its bounded process outcome. */
internal fun executeInstallationChild(
    stage: InstallationChildStage,
    command: List<String>,
    environment: Map<String, String>,
    observe: (InstallationChildObservation) -> Unit = { System.err.println(it.toJson()) },
): InstallationChildOutcome {
    var child: Process? = null
    val outcome =
        try {
            val process =
                ProcessBuilder(command)
                    .redirectInput(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .apply {
                        environment().clear()
                        environment().putAll(environment)
                    }
                    .start()
            child = process
            if (!process.waitFor(Duration.ofMillis(InstallationOperationalLimits.retirementChildTimeoutMillis))) {
                process.destroyForcibly()
                process.waitFor()
                InstallationChildOutcome.DEADLINE_EXCEEDED
            } else if (process.exitValue() == 0) InstallationChildOutcome.COMPLETED
            else InstallationChildOutcome.EXIT_REJECTED
        } catch (_: IOException) {
            InstallationChildOutcome.IO_REJECTED
        } catch (_: InterruptedException) {
            child?.destroyForcibly()
            Thread.currentThread().interrupt()
            InstallationChildOutcome.INTERRUPTED
        }
    observe(InstallationChildObservation(stage = stage, outcome = outcome))
    return outcome
}
