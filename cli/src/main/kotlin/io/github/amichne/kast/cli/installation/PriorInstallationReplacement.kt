package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.distribution.contract.configuration.RetiredConfigurationSetting
import io.github.amichne.kast.distribution.contract.configuration.SavedConfigurationDocument
import io.github.amichne.kast.distribution.managed.PriorInstallationPreparation
import io.github.amichne.kast.distribution.managed.preparePriorInstallationReplacement
import io.github.amichne.kast.distribution.managed.resetInstallationTransport
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Only historical saved configurations require the former transient enabled-owner override. */
internal fun priorServiceRetirementEnvironment(
    prior: Path,
    home: Path,
    codexHome: Path,
    path: String,
    configuration: SavedConfigurationDocument,
): Map<String, String> =
    mapOf(
        "HOME" to home.toString(),
        "PATH" to path,
        "CODEX_HOME" to codexHome.toString(),
        "KAST_CONFIGURATION_FILE" to prior.resolve("config/environment").toString(),
    ) +
        if (
            configuration.configurationSources(emptyMap()).savedInstallation.any {
                it.first == RetiredConfigurationSetting.ENABLE_APP_SERVER.key
            }
        )
            mapOf(RetiredConfigurationSetting.ENABLE_APP_SERVER.key to "1")
        else emptyMap()

/** Replacement uses installation identity, never the old executable, manifest or configuration. */
internal fun replacePriorInstallation(
    prior: Path,
    home: Path,
    observe: (InstallationChildObservation) -> Unit = { System.err.println(it.toJson()) },
): InstallationChildOutcome {
    val outcome =
        try {
            when (preparePriorInstallationReplacement(prior, home)) {
                PriorInstallationPreparation.PREPARED -> retirePriorServices(prior, home)
                PriorInstallationPreparation.FILESYSTEM_REJECTED -> InstallationChildOutcome.IO_REJECTED
            }
        } catch (_: java.io.IOException) {
            InstallationChildOutcome.IO_REJECTED
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            InstallationChildOutcome.INTERRUPTED
        }
    observe(InstallationChildObservation(stage = InstallationChildStage.PRIOR_REPLACEMENT, outcome = outcome))
    return outcome
}

/** Explicit force authority is limited to transport paths derived from the selected installation. */
internal fun resetInstallation(
    installation: Path,
    home: Path,
    observe: (InstallationChildObservation) -> Unit = { System.err.println(it.toJson()) },
): InstallationChildOutcome {
    val retired = replacePriorInstallation(installation, home, observe)
    if (retired != InstallationChildOutcome.COMPLETED) return retired
    val outcome =
        when (resetInstallationTransport(installation, home)) {
            PriorInstallationPreparation.PREPARED -> InstallationChildOutcome.COMPLETED
            PriorInstallationPreparation.FILESYSTEM_REJECTED -> InstallationChildOutcome.IO_REJECTED
        }
    observe(InstallationChildObservation(stage = InstallationChildStage.FORCE_RESET, outcome = outcome))
    return outcome
}

private const val SERVICE_NOT_FOUND = 113
private const val RETIREMENT_TIMEOUT_SECONDS = 10L

private fun retirePriorServices(prior: Path, home: Path): InstallationChildOutcome {
    val services = retireLaunchServices(prior, home)
    if (services != InstallationChildOutcome.COMPLETED) return services
    // Historical workers may not belong to launchd. Match complete path components, never PID receipts or substrings.
    val installer = ProcessHandle.current()
    val protectedProcesses = generateSequence(installer) { it.parent().orElse(null) }.map(ProcessHandle::pid).toSet()
    val processes =
        ProcessHandle.allProcesses().use { all ->
            all.filter { process -> process.pid() !in protectedProcesses && ownsProcess(process, prior) }.toList()
        }
    for (process in processes) {
        if (!process.isAlive || !ownsProcess(process, prior)) continue
        val descendants = process.descendants().use { it.toList() }
        for (owned in descendants.asReversed() + process) {
            val stopped = stopProcess(owned)
            if (stopped != InstallationChildOutcome.COMPLETED) return stopped
        }
    }
    return InstallationChildOutcome.COMPLETED
}

private fun retireLaunchServices(prior: Path, home: Path): InstallationChildOutcome {
    if (!Files.isExecutable(Path.of("/bin/launchctl"))) return InstallationChildOutcome.COMPLETED
    val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(prior.toString().toByteArray()))
    val label = "io.github.amichne.kast.broker.${digest.take(32)}"
    val uid = (Files.getAttribute(home, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Number).toLong()
    for (service in listOf("$label.login", label)) {
        val stopped = retireLaunchService(service, uid)
        if (stopped != InstallationChildOutcome.COMPLETED) return stopped
    }
    return InstallationChildOutcome.COMPLETED
}

internal fun retireLaunchService(
    service: String,
    uid: Long,
    executor: PriorLaunchctlExecutor = JdkPriorLaunchctlExecutor,
    observe: (PriorLaunchctlObservation) -> Unit = { System.err.println(it.toJson()) },
    timing: PriorRetirementTiming = PriorRetirementTiming(),
): InstallationChildOutcome =
    when (
        val observed =
            launchctl(
                stage = PriorLaunchctlStage.OBSERVE_BEFORE,
                arguments = listOf("list", service),
                executor = executor,
                observe = observe,
            )
    ) {
        is PriorLaunchctlResult.Absent -> InstallationChildOutcome.COMPLETED
        is PriorLaunchctlResult.Completed -> {
            val retired =
                launchctl(
                    stage = PriorLaunchctlStage.BOOTOUT,
                    arguments = listOf("bootout", "gui/$uid/$service"),
                    executor = executor,
                    observe = observe,
                )
            when (retired) {
                PriorLaunchctlResult.DeadlineExceeded -> InstallationChildOutcome.DEADLINE_EXCEEDED
                PriorLaunchctlResult.IoRejected -> InstallationChildOutcome.IO_REJECTED
                PriorLaunchctlResult.Interrupted -> InstallationChildOutcome.INTERRUPTED
                is PriorLaunchctlResult.ExitRejected,
                is PriorLaunchctlResult.Completed,
                is PriorLaunchctlResult.Absent ->
                    awaitLaunchServiceAbsence(
                        service = service,
                        executor = executor,
                        observe = observe,
                        timing = timing,
                    )
            }
        }
        is PriorLaunchctlResult.Rejected -> observed.outcome
    }

internal enum class PriorRetirementPause {
    CONTINUE,
    INTERRUPTED,
}

internal class PriorRetirementTiming(
    val clock: () -> Long = System::nanoTime,
    val pause: (Duration) -> PriorRetirementPause = ::pausePriorRetirement,
)

private fun pausePriorRetirement(duration: Duration): PriorRetirementPause =
    try {
        Thread.sleep(duration)
        PriorRetirementPause.CONTINUE
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        PriorRetirementPause.INTERRUPTED
    }

/** A bootout exit describes the child; only bounded observation proves service absence. */
private fun awaitLaunchServiceAbsence(
    service: String,
    executor: PriorLaunchctlExecutor,
    observe: (PriorLaunchctlObservation) -> Unit,
    timing: PriorRetirementTiming,
): InstallationChildOutcome {
    val deadline = timing.clock() + TimeUnit.SECONDS.toNanos(RETIREMENT_TIMEOUT_SECONDS)
    while (true) {
        val remaining = deadline - timing.clock()
        if (remaining <= 0) {
            observe(
                PriorLaunchctlObservation(
                    stage = PriorLaunchctlStage.OBSERVE_AFTER,
                    outcome = PriorLaunchctlResult.DeadlineExceeded,
                )
            )
            return InstallationChildOutcome.DEADLINE_EXCEEDED
        }
        when (
            val observed =
                launchctl(
                    stage = PriorLaunchctlStage.OBSERVE_AFTER,
                    arguments = listOf("list", service),
                    executor = executor,
                    observe = observe,
                    timeout = Duration.ofNanos(remaining),
                )
        ) {
            is PriorLaunchctlResult.Absent -> return InstallationChildOutcome.COMPLETED
            is PriorLaunchctlResult.Rejected -> return observed.outcome
            is PriorLaunchctlResult.Completed -> Unit
        }
        val pauseNanos = minOf(TimeUnit.MILLISECONDS.toNanos(100), deadline - timing.clock())
        if (pauseNanos <= 0) continue
        when (timing.pause(Duration.ofNanos(pauseNanos))) {
            PriorRetirementPause.CONTINUE -> Unit
            PriorRetirementPause.INTERRUPTED -> {
                observe(
                    PriorLaunchctlObservation(
                        stage = PriorLaunchctlStage.OBSERVE_AFTER,
                        outcome = PriorLaunchctlResult.Interrupted,
                    )
                )
                return InstallationChildOutcome.INTERRUPTED
            }
        }
    }
}

private fun stopProcess(process: ProcessHandle): InstallationChildOutcome {
    if (!process.isAlive) return InstallationChildOutcome.COMPLETED
    process.destroyForcibly()
    return try {
        process.onExit().get(RETIREMENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        InstallationChildOutcome.COMPLETED
    } catch (_: java.util.concurrent.TimeoutException) {
        InstallationChildOutcome.DEADLINE_EXCEEDED
    } catch (_: java.util.concurrent.ExecutionException) {
        InstallationChildOutcome.EXIT_REJECTED
    }
}

private fun ownsProcess(process: ProcessHandle, prior: Path): Boolean {
    val info = process.info()
    val arguments = info.arguments().orElse(emptyArray()).toList() + info.command().orElse("")
    return arguments
        .flatMap { it.split(java.io.File.pathSeparatorChar) }
        .any { argument ->
            try {
                val path = Path.of(argument)
                path.isAbsolute && path.normalize().startsWith(prior)
            } catch (_: java.nio.file.InvalidPathException) {
                false
            }
        }
}

@Serializable
internal enum class PriorLaunchctlStage {
    OBSERVE_BEFORE,
    BOOTOUT,
    OBSERVE_AFTER,
}

@Serializable
internal sealed interface PriorLaunchctlResult {
    @Serializable
    @SerialName("COMPLETED")
    data class Completed(val exitCode: Int = 0) : PriorLaunchctlResult {
        init {
            require(exitCode == 0)
        }
    }

    @Serializable
    @SerialName("ABSENT")
    data class Absent(val exitCode: Int = SERVICE_NOT_FOUND) : PriorLaunchctlResult {
        init {
            require(exitCode == SERVICE_NOT_FOUND)
        }
    }

    sealed interface Rejected : PriorLaunchctlResult {
        val outcome: InstallationChildOutcome
    }

    @Serializable
    @SerialName("EXIT_REJECTED")
    data class ExitRejected(val exitCode: Int) : Rejected {
        init {
            require(exitCode != 0 && exitCode != SERVICE_NOT_FOUND)
        }

        override val outcome: InstallationChildOutcome
            get() = InstallationChildOutcome.EXIT_REJECTED
    }

    @Serializable
    @SerialName("DEADLINE_EXCEEDED")
    data object DeadlineExceeded : Rejected {
        override val outcome: InstallationChildOutcome
            get() = InstallationChildOutcome.DEADLINE_EXCEEDED
    }

    @Serializable
    @SerialName("IO_REJECTED")
    data object IoRejected : Rejected {
        override val outcome: InstallationChildOutcome
            get() = InstallationChildOutcome.IO_REJECTED
    }

    @Serializable
    @SerialName("INTERRUPTED")
    data object Interrupted : Rejected {
        override val outcome: InstallationChildOutcome
            get() = InstallationChildOutcome.INTERRUPTED
    }
}

@Serializable
internal data class PriorLaunchctlObservation(
    val event: String = "kast_installation_retirement",
    val stage: PriorLaunchctlStage,
    val outcome: PriorLaunchctlResult,
) {
    fun toJson(): String = Json { encodeDefaults = true }.encodeToString(serializer(), this)
}

internal fun interface PriorLaunchctlExecutor {
    fun execute(arguments: List<String>, timeout: Duration): PriorLaunchctlResult
}

private fun launchctl(
    stage: PriorLaunchctlStage,
    arguments: List<String>,
    executor: PriorLaunchctlExecutor,
    observe: (PriorLaunchctlObservation) -> Unit,
    timeout: Duration = Duration.ofSeconds(RETIREMENT_TIMEOUT_SECONDS),
): PriorLaunchctlResult {
    val result = executor.execute(arguments, timeout)
    observe(PriorLaunchctlObservation(stage = stage, outcome = result))
    return result
}

private object JdkPriorLaunchctlExecutor : PriorLaunchctlExecutor {
    override fun execute(arguments: List<String>, timeout: Duration): PriorLaunchctlResult {
        var child: Process? = null
        return try {
            val process =
                ProcessBuilder(listOf("/bin/launchctl") + arguments)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            child = process
            if (!process.waitFor(timeout)) PriorLaunchctlResult.DeadlineExceeded
            else
                when (val exitCode = process.exitValue()) {
                    0 -> PriorLaunchctlResult.Completed()
                    SERVICE_NOT_FOUND -> PriorLaunchctlResult.Absent()
                    else -> PriorLaunchctlResult.ExitRejected(exitCode)
                }
        } catch (_: java.io.IOException) {
            PriorLaunchctlResult.IoRejected
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            PriorLaunchctlResult.Interrupted
        } finally {
            child?.let { if (it.isAlive) it.destroyForcibly() }
        }
    }
}

internal fun admitReplacement(outcome: InstallationChildOutcome): Refinement<Unit, InstallationFailure> =
    when (outcome) {
        InstallationChildOutcome.COMPLETED -> Refinement.Refined(Unit)
        InstallationChildOutcome.EXIT_REJECTED -> Refinement.Rejected(InstallationFailure.REPLACEMENT_EXIT_REJECTED)
        InstallationChildOutcome.DEADLINE_EXCEEDED ->
            Refinement.Rejected(InstallationFailure.REPLACEMENT_DEADLINE_EXCEEDED)
        InstallationChildOutcome.IO_REJECTED -> Refinement.Rejected(InstallationFailure.REPLACEMENT_IO_REJECTED)
        InstallationChildOutcome.INTERRUPTED -> Refinement.Rejected(InstallationFailure.INTERRUPTED)
    }
