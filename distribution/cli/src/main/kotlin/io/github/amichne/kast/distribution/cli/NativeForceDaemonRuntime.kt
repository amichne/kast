package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

internal object NativeForceDaemonRuntime : ForceDaemonRuntime {
    private const val MAXIMUM_PROCESSES = 16384L
    private const val LAUNCHCTL_SECONDS = 10L

    override fun launch(command: ResetLaunchCommand, home: Path): ResetCommandObservation =
        launchObserved(command, home) { System.err.println(it.asJson()) }

    fun launchObserved(
        command: ResetLaunchCommand,
        home: Path,
        observe: (ResetLaunchObservation) -> Unit,
    ): ResetCommandObservation {
        val result = launchCommand(command, home)
        observe(ResetLaunchObservation.from(command, result))
        return result
    }

    private fun launchCommand(command: ResetLaunchCommand, home: Path): ResetCommandObservation {
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
            val allowance =
                when (command) {
                    is ResetLaunchCommand.Inspect -> minOf(command.allowance, Duration.ofSeconds(LAUNCHCTL_SECONDS))
                    is ResetLaunchCommand.Bootout -> Duration.ofSeconds(LAUNCHCTL_SECONDS)
                }
            if (allowance <= Duration.ZERO) return ResetCommandObservation.DeadlineExceeded
            val process =
                ProcessBuilder(listOf("/bin/launchctl") + arguments)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            try {
                if (process.waitFor(allowance.toNanos(), TimeUnit.NANOSECONDS))
                    ResetCommandObservation.Exited(process.exitValue())
                else ResetCommandObservation.DeadlineExceeded
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            ResetCommandObservation.Interrupted
        } catch (_: Exception) {
            ResetCommandObservation.Unavailable
        }
    }

    override fun processes(): ResetProcessTable = processesObserved { System.err.println(it.asJson()) }

    fun processesObserved(observe: (ResetInventoryObservation) -> Unit): ResetProcessTable =
        try {
            val current = ProcessHandle.current()
            val user = current.info().user().orElse(null)
            if (user == null)
                inventoryRejected(current.pid(), ResetInventoryRejection.CURRENT_USER_UNAVAILABLE, observe)
            else collectCurrentProcesses(user, observe)
        } catch (_: Exception) {
            inventoryRejected(ProcessHandle.current().pid(), ResetInventoryRejection.EFFECT_UNAVAILABLE, observe)
        }

    private fun collectCurrentProcesses(user: String, observe: (ResetInventoryObservation) -> Unit): ResetProcessTable =
        ProcessHandle.allProcesses().use { stream ->
            val handles = stream.limit(MAXIMUM_PROCESSES + 1).toList()
            if (handles.size > MAXIMUM_PROCESSES)
                inventoryRejected(ProcessHandle.current().pid(), ResetInventoryRejection.CAPACITY_EXCEEDED, observe)
            else collectSnapshots(handles, user, observe)
        }

    internal fun collectSnapshots(
        handles: List<ProcessHandle>,
        user: String,
        observe: (ResetInventoryObservation) -> Unit,
        processState: (ProcessHandle) -> ResetObservedProcessState = ::observeResetProcessState,
    ): ResetProcessTable {
        val snapshots = mutableListOf<LifecycleProcessSnapshot>()
        for (handle in handles) {
            when (val observation = snapshot(handle, user, processState)) {
                is NativeResetSnapshot.Selected -> snapshots += observation.snapshot
                NativeResetSnapshot.Unrelated -> Unit
                is NativeResetSnapshot.Unavailable ->
                    return inventoryRejected(handle.pid(), observation.reason, observe)
            }
        }
        observe(ResetInventoryObservation(ResetInventoryOutcome.Observed(snapshots.size)))
        return ResetProcessTable.Observed(snapshots)
    }

    private fun inventoryRejected(
        pid: Long,
        reason: ResetInventoryRejection,
        observe: (ResetInventoryObservation) -> Unit,
    ): ResetProcessTable {
        observe(ResetInventoryObservation(ResetInventoryOutcome.Rejected(pid, reason)))
        return ResetProcessTable.Unavailable
    }

    private fun snapshot(
        handle: ProcessHandle,
        user: String,
        processState: (ProcessHandle) -> ResetObservedProcessState,
    ): NativeResetSnapshot {
        if (!handle.isAlive) return NativeResetSnapshot.Unrelated
        val info = handle.info()
        val owner =
            info.user().orElse(null)
                ?: return if (handle.isAlive) NativeResetSnapshot.Unavailable(ResetInventoryRejection.OWNER_UNAVAILABLE)
                else NativeResetSnapshot.Unrelated
        if (owner != user) return NativeResetSnapshot.Unrelated
        val command = info.command().orElse(null)
        if (command == null) {
            return if (!handle.isAlive || processState(handle) == ResetObservedProcessState.EXITED)
                NativeResetSnapshot.Unrelated
            else NativeResetSnapshot.Unavailable(ResetInventoryRejection.COMMAND_UNAVAILABLE)
        }
        val args = info.arguments().orElse(null)
        if (args == null && Path.of(command).fileName.toString() == "java")
            return NativeResetSnapshot.Unavailable(ResetInventoryRejection.ARGUMENTS_UNAVAILABLE)
        return NativeResetSnapshot.Selected(
            LifecycleProcessSnapshot(
                handle.pid(),
                info.startInstant().map { it.toEpochMilli() }.orElse(0L),
                command,
                args?.toList().orEmpty(),
            )
        )
    }

    override fun capture(process: ResetScopedProcess): ResetSharedSocketCapture =
        captureObserved(process) { System.err.println(it.asJson()) }

    fun captureObserved(
        process: ResetScopedProcess,
        observe: (ResetProcessObservation) -> Unit,
    ): ResetSharedSocketCapture = NativeResetProcessRetirement.captureObserved(process, observe)

    override fun retire(process: ResetSharedSocketCapture.Captured, allowance: Duration): LifecycleProcessObservation =
        retireCapturedObserved(process, allowance) { System.err.println(it.asJson()) }

    fun retireObserved(
        process: ResetScopedProcess,
        allowance: Duration,
        observe: (ResetProcessObservation) -> Unit,
    ): LifecycleProcessObservation = NativeResetProcessRetirement.retireObserved(process, allowance, observe)

    fun retireCapturedObserved(
        process: ResetSharedSocketCapture.Captured,
        allowance: Duration,
        observe: (ResetProcessObservation) -> Unit,
    ): LifecycleProcessObservation = NativeResetProcessRetirement.retireCapturedObserved(process, allowance, observe)

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

    data class Unavailable(val reason: ResetInventoryRejection) : NativeResetSnapshot
}
