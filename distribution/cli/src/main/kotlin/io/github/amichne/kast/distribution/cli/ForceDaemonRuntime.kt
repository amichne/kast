package io.github.amichne.kast.distribution.cli

import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

@JvmInline
internal value class ResetServiceLabel private constructor(val value: String) {
    companion object {
        fun selected(installation: Path): ResetServiceLabel {
            val hash =
                MessageDigest.getInstance("SHA-256")
                    .digest(installation.toString().toByteArray())
                    .joinToString("") { "%02x".format(it) }
                    .take(32)
            return ResetServiceLabel("io.github.amichne.kast.broker.$hash")
        }
    }
}

internal enum class ResetLaunchVariant(val suffix: String) {
    LEGACY_LOGIN(".login"),
    CURRENT(""),
}

internal sealed interface ResetLaunchCommand {
    val label: ResetServiceLabel

    data class Inspect(override val label: ResetServiceLabel, val variant: ResetLaunchVariant) : ResetLaunchCommand

    data class Bootout(override val label: ResetServiceLabel, val variant: ResetLaunchVariant) : ResetLaunchCommand
}

internal sealed interface ResetCommandObservation {
    data class Exited(val code: Int) : ResetCommandObservation

    data object Unavailable : ResetCommandObservation
}

internal sealed interface ResetProcessTable {
    data class Observed(val processes: List<LifecycleProcessSnapshot>) : ResetProcessTable

    data object Unavailable : ResetProcessTable
}

internal sealed interface ResetActivationObservation {
    data class Ready(val version: String, val generation: String) : ResetActivationObservation

    data object Unavailable : ResetActivationObservation
}

internal interface ForceDaemonRuntime {
    fun launch(command: ResetLaunchCommand, home: Path): ResetCommandObservation

    fun processes(): ResetProcessTable

    fun retire(process: ResetScopedProcess, allowance: Duration): LifecycleProcessObservation

    fun activate(installation: Path, home: Path, environment: Map<String, String>): LifecycleChildObservation

    fun readiness(installation: Path): ResetActivationObservation
}

internal class ResetScopedProcess private constructor(val snapshot: LifecycleProcessSnapshot) {
    companion object {
        private val mains =
            setOf(
                "io.github.amichne.kast.cli.KastDaemonMain",
                "io.github.amichne.kast.cli.KastServiceMain",
                "io.github.amichne.kast.appserver.KastCodexMain",
                "io.github.amichne.kast.appserver.KastCodexMainKt",
                "io.github.amichne.kast.cli.rpc.KastToolRpcMain",
                "io.github.amichne.kast.cli.mcp.KastMcpMain",
            )

        fun select(snapshot: LifecycleProcessSnapshot, root: Path): ResetProcessSelection {
            val command =
                try {
                    Path.of(snapshot.command)
                } catch (_: IllegalArgumentException) {
                    return ResetProcessSelection.Unrelated
                }
            val direct = directSelected(command, root) || shellSelected(command, snapshot.arguments, root)
            val index = snapshot.arguments.indexOfFirst { it == "-cp" || it == "-classpath" }
            val java =
                command.fileName.toString() == "java" &&
                    index >= 0 &&
                    index + 2 < snapshot.arguments.size &&
                    snapshot.arguments[index + 2] in mains &&
                    classpathSelected(snapshot.arguments[index + 1], root)
            return when {
                !direct && !java -> ResetProcessSelection.Unrelated
                snapshot.pid <= 0 || snapshot.startEpochMillis <= 0 -> ResetProcessSelection.Rejected
                else -> ResetProcessSelection.Selected(ResetScopedProcess(snapshot))
            }
        }

        private fun directSelected(command: Path, root: Path): Boolean {
            if (!command.isAbsolute || command.normalize() != command) return false
            return command.startsWith(root) &&
                command.fileName.toString() in
                    setOf(
                        "kast-daemon",
                        "kast-service",
                        "kast-mcp",
                        "kast-tool-rpc",
                        "kast-codex",
                        "kast-mcp-complete",
                        "kast-tool-rpc-complete",
                        "kast-codex-complete",
                    )
        }

        private fun shellSelected(command: Path, arguments: List<String>, root: Path): Boolean {
            if (command.fileName.toString() !in setOf("sh", "bash", "zsh") || arguments.isEmpty()) return false
            val script =
                try {
                    Path.of(arguments.first())
                } catch (_: IllegalArgumentException) {
                    return false
                }
            return directSelected(script, root)
        }

        private fun classpathSelected(raw: String, root: Path): Boolean =
            raw.split(':').all { entry ->
                try {
                    val path = Path.of(entry)
                    path.isAbsolute && path.normalize() == path && path.startsWith(root)
                } catch (_: IllegalArgumentException) {
                    false
                }
            }
    }
}

internal sealed interface ResetProcessSelection {
    data class Selected(val process: ResetScopedProcess) : ResetProcessSelection

    data object Unrelated : ResetProcessSelection

    data object Rejected : ResetProcessSelection
}

internal sealed interface ResetEffect {
    data object Completed : ResetEffect

    data class Rejected(val failure: ForceResetFailure) : ResetEffect
}

internal class ForceDaemonRetirement(private val runtime: ForceDaemonRuntime) {
    fun retire(root: Path, home: Path, retained: RetainedResetStorage = RetainedResetStorage.Missing): ResetEffect {
        val label = ResetServiceLabel.selected(root.resolve("installation"))
        for (variant in ResetLaunchVariant.entries) {
            when (val job = retireJob(label, variant, home)) {
                ResetEffect.Completed -> Unit
                is ResetEffect.Rejected -> return job
            }
        }
        val removed = ResetLoginEntries.remove(home, label)
        if (removed is ResetEffect.Rejected) return removed
        val scope =
            when (retained) {
                RetainedResetStorage.Missing -> listOf(root)
                is RetainedResetStorage.Held -> listOf(root, retained.directory.resolve("payload"))
            }
        return retireProcesses(scope)
    }

    private fun retireJob(label: ResetServiceLabel, variant: ResetLaunchVariant, home: Path): ResetEffect {
        val before = runtime.launch(ResetLaunchCommand.Inspect(label, variant), home)
        if (before !is ResetCommandObservation.Exited) return rejected(ForceResetFailure.LAUNCH_JOB_REJECTED)
        if (before.code == SERVICE_ABSENT) return ResetEffect.Completed
        if (before.code != 0) return rejected(ForceResetFailure.LAUNCH_JOB_REJECTED)
        runtime.launch(ResetLaunchCommand.Bootout(label, variant), home)
        val after = runtime.launch(ResetLaunchCommand.Inspect(label, variant), home)
        return if (after is ResetCommandObservation.Exited && after.code == SERVICE_ABSENT) ResetEffect.Completed
        else rejected(ForceResetFailure.LAUNCH_JOB_REJECTED)
    }

    private fun retireProcesses(scope: List<Path>): ResetEffect {
        val table = runtime.processes()
        if (table !is ResetProcessTable.Observed || table.processes.size > MAXIMUM_PROCESSES)
            return rejected(ForceResetFailure.PROCESS_OBSERVATION_REJECTED)
        val deadline = System.nanoTime() + Duration.ofSeconds(RETIREMENT_SECONDS).toNanos()
        for (snapshot in table.processes) {
            when (val selected = select(snapshot, scope)) {
                ResetProcessSelection.Unrelated -> Unit
                ResetProcessSelection.Rejected -> return rejected(ForceResetFailure.PROCESS_OBSERVATION_REJECTED)
                is ResetProcessSelection.Selected -> {
                    val result = retireSelected(selected.process, deadline)
                    if (result is ResetEffect.Rejected) return result
                }
            }
        }
        val final = runtime.processes()
        return if (
            final is ResetProcessTable.Observed &&
                final.processes.size <= MAXIMUM_PROCESSES &&
                final.processes.all {
                    select(it, scope) == ResetProcessSelection.Unrelated
                }
        )
            ResetEffect.Completed
        else rejected(ForceResetFailure.PROCESS_RETIREMENT_REJECTED)
    }

    private fun select(snapshot: LifecycleProcessSnapshot, scope: List<Path>): ResetProcessSelection {
        for (root in scope) {
            when (val selected = ResetScopedProcess.select(snapshot, root)) {
                is ResetProcessSelection.Selected -> return selected
                ResetProcessSelection.Rejected -> return selected
                ResetProcessSelection.Unrelated -> Unit
            }
        }
        return ResetProcessSelection.Unrelated
    }

    private fun retireSelected(process: ResetScopedProcess, deadline: Long): ResetEffect {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) return rejected(ForceResetFailure.PROCESS_RETIREMENT_REJECTED)
        val observed = runtime.retire(process, Duration.ofNanos(remaining))
        return if (incarnationRetired(process.snapshot, observed)) ResetEffect.Completed
        else rejected(ForceResetFailure.PROCESS_RETIREMENT_REJECTED)
    }

    private fun incarnationRetired(prior: LifecycleProcessSnapshot, observed: LifecycleProcessObservation): Boolean =
        when (observed) {
            LifecycleProcessObservation.Absent -> true
            LifecycleProcessObservation.Unavailable -> false
            is LifecycleProcessObservation.Present ->
                observed.snapshot.pid == prior.pid && observed.snapshot.startEpochMillis != prior.startEpochMillis
        }

    private fun rejected(failure: ForceResetFailure) = ResetEffect.Rejected(failure)

    companion object {
        private const val MAXIMUM_PROCESSES = 16384
        private const val SERVICE_ABSENT = 113
        private const val RETIREMENT_SECONDS = 30L
    }
}
