package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal object NativeForceDaemonRuntime : ForceDaemonRuntime {
    private const val MAXIMUM_PROCESSES = 16384L
    private const val LAUNCHCTL_SECONDS = 10L

    override fun launch(command: ResetLaunchCommand, home: Path): ResetCommandObservation {
        if (!Files.isExecutable(Path.of("/bin/launchctl"))) return ResetCommandObservation.Unavailable
        val label =
            command.label.value +
                when (command) {
                    is ResetLaunchCommand.Inspect -> command.variant.suffix
                    is ResetLaunchCommand.Bootout -> command.variant.suffix
                }
        return try {
            val arguments =
                when (command) {
                    is ResetLaunchCommand.Inspect -> listOf("list", label)
                    is ResetLaunchCommand.Bootout -> {
                        val uid = (Files.getAttribute(home, "unix:uid", NOFOLLOW_LINKS) as Number).toLong()
                        listOf("bootout", "gui/$uid/$label")
                    }
                }
            val process =
                ProcessBuilder(listOf("/bin/launchctl") + arguments)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            try {
                if (process.waitFor(LAUNCHCTL_SECONDS, TimeUnit.SECONDS))
                    ResetCommandObservation.Exited(process.exitValue())
                else ResetCommandObservation.Unavailable
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            ResetCommandObservation.Unavailable
        } catch (_: Exception) {
            ResetCommandObservation.Unavailable
        }
    }

    override fun processes(): ResetProcessTable =
        try {
            val user = ProcessHandle.current().info().user().orElseThrow()
            ProcessHandle.allProcesses().use { stream ->
                val handles = stream.limit(MAXIMUM_PROCESSES + 1).toList()
                if (handles.size > MAXIMUM_PROCESSES) return ResetProcessTable.Unavailable
                collectSnapshots(handles, user)
            }
        } catch (_: Exception) {
            ResetProcessTable.Unavailable
        }

    private fun collectSnapshots(handles: List<ProcessHandle>, user: String): ResetProcessTable {
        val snapshots = mutableListOf<LifecycleProcessSnapshot>()
        for (handle in handles) {
            when (val observation = snapshot(handle, user)) {
                is NativeResetSnapshot.Selected -> snapshots += observation.snapshot
                NativeResetSnapshot.Unrelated -> Unit
                NativeResetSnapshot.Unavailable -> return ResetProcessTable.Unavailable
            }
        }
        return ResetProcessTable.Observed(snapshots)
    }

    private fun snapshot(handle: ProcessHandle, user: String): NativeResetSnapshot {
        if (!handle.isAlive) return NativeResetSnapshot.Unrelated
        val info = handle.info()
        val owner =
            info.user().orElse(null)
                ?: return if (handle.isAlive) NativeResetSnapshot.Unavailable else NativeResetSnapshot.Unrelated
        if (owner != user) return NativeResetSnapshot.Unrelated
        val command =
            info.command().orElse(null)
                ?: return if (handle.isAlive) NativeResetSnapshot.Unavailable else NativeResetSnapshot.Unrelated
        val args = info.arguments().orElse(null)
        if (args == null && Path.of(command).fileName.toString() == "java") return NativeResetSnapshot.Unavailable
        return NativeResetSnapshot.Selected(
            LifecycleProcessSnapshot(
                handle.pid(),
                info.startInstant().map { it.toEpochMilli() }.orElse(0L),
                command,
                args?.toList().orEmpty(),
            )
        )
    }

    override fun retire(process: ResetScopedProcess, allowance: Duration): LifecycleProcessObservation =
        retireObserved(process, allowance) { System.err.println(it.asJson()) }

    fun retireObserved(
        process: ResetScopedProcess,
        allowance: Duration,
        observe: (ResetProcessObservation) -> Unit,
    ): LifecycleProcessObservation {
        val prior = process.snapshot
        when (val admission = revalidate(prior, observe)) {
            NativeResetAdmission.Admitted -> Unit
            is NativeResetAdmission.Finished -> return admission.observation
        }
        val handle = ProcessHandle.of(prior.pid).orElse(null) ?: return LifecycleProcessObservation.Absent
        return retireCurrent(prior, handle, allowance, observe)
    }

    private fun retireCurrent(
        prior: LifecycleProcessSnapshot,
        handle: ProcessHandle,
        allowance: Duration,
        observe: (ResetProcessObservation) -> Unit,
    ): LifecycleProcessObservation =
        try {
            val dedicated =
                when (val children = dedicatedChildren(handle)) {
                    is ResetProcessTable.Observed -> children.processes
                    ResetProcessTable.Unavailable -> {
                        observe(
                            ResetProcessObservation(
                                prior.pid,
                                ResetProcessStage.CHILDREN,
                                ResetProcessResult.OBSERVATION_REJECTED,
                            )
                        )
                        return LifecycleProcessObservation.Unavailable
                    }
                }
            val deadline = System.nanoTime() + allowance.toNanos()
            for (snapshot in dedicated + prior) {
                if (terminateSnapshot(snapshot, deadline, observe) == NativeTermination.REJECTED)
                    return LifecycleProcessObservation.Unavailable
            }
            verifyRetirement(prior.pid, observe)
        } catch (_: TimeoutException) {
            observe(ResetProcessObservation(prior.pid, ResetProcessStage.WAIT, ResetProcessResult.DEADLINE_EXCEEDED))
            LifecycleProcessObservation.Unavailable
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            observe(ResetProcessObservation(prior.pid, ResetProcessStage.WAIT, ResetProcessResult.INTERRUPTED))
            LifecycleProcessObservation.Unavailable
        } catch (_: Exception) {
            observe(ResetProcessObservation(prior.pid, ResetProcessStage.WAIT, ResetProcessResult.WAIT_REJECTED))
            LifecycleProcessObservation.Unavailable
        }

    private fun verifyRetirement(pid: Long, observe: (ResetProcessObservation) -> Unit): LifecycleProcessObservation {
        val result = NativeLifecycleProcesses.observe(pid)
        observe(
            ResetProcessObservation(
                pid,
                ResetProcessStage.VERIFY,
                if (result == LifecycleProcessObservation.Absent) ResetProcessResult.RETIRED
                else ResetProcessResult.OBSERVATION_REJECTED,
            )
        )
        return result
    }

    private fun revalidate(
        prior: LifecycleProcessSnapshot,
        observe: (ResetProcessObservation) -> Unit,
    ): NativeResetAdmission {
        val current = NativeLifecycleProcesses.observe(prior.pid)
        val admission = NativeResetAdmission.admit(prior, current)
        val outcome =
            when (admission) {
                NativeResetAdmission.Admitted -> ResetProcessResult.IDENTITY_VERIFIED
                is NativeResetAdmission.Finished -> admission.outcome
            }
        observe(ResetProcessObservation(prior.pid, ResetProcessStage.REVALIDATE, outcome))
        return admission
    }

    private fun dedicatedChildren(handle: ProcessHandle): ResetProcessTable {
        val children = handle.descendants().use { stream -> stream.limit(MAXIMUM_PROCESSES + 1).toList() }
        if (children.size > MAXIMUM_PROCESSES) return ResetProcessTable.Unavailable
        val snapshots = mutableListOf<LifecycleProcessSnapshot>()
        for (child in children.filter(::dedicatedCodexChild)) {
            when (val observed = NativeLifecycleProcesses.observe(child.pid())) {
                LifecycleProcessObservation.Absent -> Unit
                LifecycleProcessObservation.Unavailable -> return ResetProcessTable.Unavailable
                is LifecycleProcessObservation.Present -> {
                    if (observed.snapshot.startEpochMillis <= 0) return ResetProcessTable.Unavailable
                    snapshots += observed.snapshot
                }
            }
        }
        return ResetProcessTable.Observed(snapshots)
    }

    private fun terminateSnapshot(
        snapshot: LifecycleProcessSnapshot,
        deadline: Long,
        observe: (ResetProcessObservation) -> Unit,
    ): NativeTermination {
        val handle = ProcessHandle.of(snapshot.pid).orElse(null) ?: return NativeTermination.RETIRED
        val admission = NativeResetAdmission.admit(snapshot, NativeLifecycleProcesses.observe(handle))
        when (admission) {
            NativeResetAdmission.Admitted -> Unit
            is NativeResetAdmission.Finished -> {
                observe(ResetProcessObservation(snapshot.pid, ResetProcessStage.SIGNAL, admission.outcome))
                return when (admission.observation) {
                    LifecycleProcessObservation.Absent,
                    is LifecycleProcessObservation.Present -> NativeTermination.RETIRED
                    LifecycleProcessObservation.Unavailable -> NativeTermination.REJECTED
                }
            }
        }
        if (!handle.destroyForcibly()) {
            observe(ResetProcessObservation(snapshot.pid, ResetProcessStage.SIGNAL, ResetProcessResult.SIGNAL_REJECTED))
            return NativeTermination.REJECTED
        }
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) {
            observe(ResetProcessObservation(snapshot.pid, ResetProcessStage.WAIT, ResetProcessResult.DEADLINE_EXCEEDED))
            return NativeTermination.REJECTED
        }
        handle.onExit().get(remaining, TimeUnit.NANOSECONDS)
        return if (!handle.isAlive) NativeTermination.RETIRED
        else {
            observe(ResetProcessObservation(snapshot.pid, ResetProcessStage.WAIT, ResetProcessResult.WAIT_REJECTED))
            NativeTermination.REJECTED
        }
    }

    private fun dedicatedCodexChild(handle: ProcessHandle): Boolean {
        val info = handle.info()
        val command = info.command().orElse("")
        val arguments = info.arguments().orElse(emptyArray())
        return command.isNotEmpty() &&
            Path.of(command).fileName.toString().startsWith("codex") &&
            arguments.firstOrNull() == "app-server"
    }

    override fun activate(installation: Path, home: Path, environment: Map<String, String>): LifecycleChildObservation {
        val selectedEnvironment =
            environment.filterKeys { it in setOf("PATH", "JAVA_HOME", "CODEX_EXECUTABLE", "TMPDIR") } +
                mapOf(
                    "HOME" to home.toString(),
                    "KAST_CONFIGURATION_FILE" to installation.resolve("config/environment").toString(),
                )
        return NativeLifecycleChildExecutor.execute(
            listOf(
                installation.resolve("share/kast/libexec/kast-service").toString(),
                "bootstrap",
            ),
            installation,
            selectedEnvironment,
        )
    }

    override fun readiness(installation: Path): ResetActivationObservation =
        when (val read = observeRuntime(installation)) {
            is PassiveRuntimeObservation.Observed -> {
                val version = read.loadedVersion.value
                if (version != null && read.loadedVersion.state == ObservationState.VERIFIED)
                    ResetActivationObservation.Ready(version, read.generation.value)
                else ResetActivationObservation.Unavailable
            }
            is PassiveRuntimeObservation.Rejected -> ResetActivationObservation.Unavailable
        }

    private const val SERVICE_ABSENT = 113
}

private sealed interface NativeResetSnapshot {
    data class Selected(val snapshot: LifecycleProcessSnapshot) : NativeResetSnapshot

    data object Unrelated : NativeResetSnapshot

    data object Unavailable : NativeResetSnapshot
}

private sealed interface NativeResetAdmission {
    data object Admitted : NativeResetAdmission

    data class Finished(val observation: LifecycleProcessObservation, val outcome: ResetProcessResult) :
        NativeResetAdmission

    companion object {
        fun admit(prior: LifecycleProcessSnapshot, current: LifecycleProcessObservation): NativeResetAdmission =
            when (current) {
                LifecycleProcessObservation.Absent -> Finished(current, ResetProcessResult.RETIRED)
                LifecycleProcessObservation.Unavailable -> Finished(current, ResetProcessResult.OBSERVATION_REJECTED)
                is LifecycleProcessObservation.Present ->
                    when {
                        current.snapshot.pid != prior.pid ->
                            Finished(LifecycleProcessObservation.Unavailable, ResetProcessResult.IDENTITY_REJECTED)
                        current.snapshot.startEpochMillis != prior.startEpochMillis ->
                            Finished(current, ResetProcessResult.INCARNATION_CHANGED)
                        current.snapshot != prior ->
                            Finished(LifecycleProcessObservation.Unavailable, ResetProcessResult.IDENTITY_REJECTED)
                        else -> Admitted
                    }
            }
    }
}

private enum class NativeTermination {
    RETIRED,
    REJECTED,
}
