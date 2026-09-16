package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.TimeUnit

/** Replacement uses installation identity, never the old executable, manifest or configuration. */
internal fun replacePriorInstallation(
    prior: Path,
    home: Path,
    observe: (InstallationChildObservation) -> Unit = { System.err.println(it.toJson()) },
): InstallationChildOutcome {
    val outcome =
        try {
            if (Files.isDirectory(prior, LinkOption.NOFOLLOW_LINKS) && prior.toRealPath() == prior) {
                Files.newByteChannel(
                        prior.resolve(".recovery-detached"),
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                    .use {}
            }
            retirePriorServices(prior, home)
        } catch (_: java.io.IOException) {
            InstallationChildOutcome.IO_REJECTED
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            InstallationChildOutcome.INTERRUPTED
        }
    observe(InstallationChildObservation(stage = InstallationChildStage.PRIOR_REPLACEMENT, outcome = outcome))
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
    Files.deleteIfExists(home.resolve("Library/LaunchAgents/$label.login.plist"))
    return InstallationChildOutcome.COMPLETED
}

private fun retireLaunchService(service: String, uid: Long): InstallationChildOutcome =
    when (val observed = launchctl(listOf("list", service))) {
        LaunchctlResult.Absent -> InstallationChildOutcome.COMPLETED
        LaunchctlResult.Completed -> {
            val retired = launchctl(listOf("bootout", "gui/$uid/$service"))
            when (retired) {
                is LaunchctlResult.Rejected -> retired.outcome
                LaunchctlResult.Completed,
                LaunchctlResult.Absent ->
                    when (val remaining = launchctl(listOf("list", service))) {
                        LaunchctlResult.Absent -> InstallationChildOutcome.COMPLETED
                        LaunchctlResult.Completed -> InstallationChildOutcome.EXIT_REJECTED
                        is LaunchctlResult.Rejected -> remaining.outcome
                    }
            }
        }
        is LaunchctlResult.Rejected -> observed.outcome
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

private sealed interface LaunchctlResult {
    data object Completed : LaunchctlResult

    data object Absent : LaunchctlResult

    data class Rejected(val outcome: InstallationChildOutcome) : LaunchctlResult
}

private fun launchctl(arguments: List<String>): LaunchctlResult {
    val process =
        ProcessBuilder(listOf("/bin/launchctl") + arguments)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    try {
        if (!process.waitFor(Duration.ofSeconds(RETIREMENT_TIMEOUT_SECONDS)))
            return LaunchctlResult.Rejected(InstallationChildOutcome.DEADLINE_EXCEEDED)
        return when (process.exitValue()) {
            0 -> LaunchctlResult.Completed
            SERVICE_NOT_FOUND -> LaunchctlResult.Absent
            else -> LaunchctlResult.Rejected(InstallationChildOutcome.EXIT_REJECTED)
        }
    } finally {
        if (process.isAlive) process.destroyForcibly()
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
