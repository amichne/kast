package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstalledToolInvocation
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

internal data class LifecycleProcessSnapshot(
    val pid: Long,
    val startEpochMillis: Long,
    val command: String,
    val arguments: List<String>,
)

internal sealed interface LifecycleProcessObservation {
    data object Absent : LifecycleProcessObservation

    data object Unavailable : LifecycleProcessObservation

    data class Present(val snapshot: LifecycleProcessSnapshot) : LifecycleProcessObservation
}

/** An admitted record and exact process incarnation, including the installed main class and classpath. */
internal class OwnedLifecycleProcess private constructor(val snapshot: LifecycleProcessSnapshot) {
    companion object {
        fun admit(
            record: InstalledToolInvocation,
            observation: LifecycleProcessObservation,
            installation: Path,
        ): ShutdownProcessAdmission {
            if (record.schemaVersion != 1 || record.pid <= 0 || record.startEpochMillis <= 0)
                return ShutdownProcessAdmission.Rejected
            return when (observation) {
                LifecycleProcessObservation.Absent -> ShutdownProcessAdmission.Finished
                LifecycleProcessObservation.Unavailable -> ShutdownProcessAdmission.Rejected
                is LifecycleProcessObservation.Present -> {
                    val snapshot = observation.snapshot
                    if (snapshot.pid != record.pid) ShutdownProcessAdmission.Rejected
                    else if (snapshot.startEpochMillis != record.startEpochMillis) ShutdownProcessAdmission.Finished
                    else if (
                        Path.of(snapshot.command).fileName.toString() != "java" ||
                            !ownsArguments(snapshot.arguments, installation)
                    )
                        ShutdownProcessAdmission.Rejected
                    else ShutdownProcessAdmission.Owned(OwnedLifecycleProcess(snapshot))
                }
            }
        }

        private fun ownsArguments(arguments: List<String>, installation: Path): Boolean {
            val main = arguments.indexOfFirst {
                it in
                    setOf(
                        "io.github.amichne.kast.cli.rpc.KastToolRpcMain",
                        "io.github.amichne.kast.cli.mcp.KastMcpMain",
                    )
            }
            val classpath = arguments.indexOfFirst { it == "-classpath" || it == "-cp" }
            if (classpath < 0 || classpath + 2 != main) return false
            val entries = arguments[classpath + 1].split(':')
            return entries.isNotEmpty() &&
                entries.all { raw ->
                    try {
                        val path = Path.of(raw)
                        path.isAbsolute &&
                            path.normalize() == path &&
                            path.parent == installation.resolve("lib") &&
                            path.fileName.toString().endsWith(".jar")
                    } catch (_: IllegalArgumentException) {
                        false
                    }
                }
        }
    }
}

internal sealed interface ShutdownProcessAdmission {
    data object Finished : ShutdownProcessAdmission

    data object Rejected : ShutdownProcessAdmission

    data class Owned(val process: OwnedLifecycleProcess) : ShutdownProcessAdmission
}

internal interface LifecycleProcesses {
    fun observe(pid: Long): LifecycleProcessObservation

    fun retire(process: OwnedLifecycleProcess, operation: LifecycleOperation, allowance: Duration): LifecycleEffect

    fun selectedHost(executable: Path): LifecycleEffect
}

private const val MAXIMUM_HOST_PROCESS_OBSERVATIONS = 16384L

internal object NativeLifecycleProcesses : LifecycleProcesses {
    override fun observe(pid: Long): LifecycleProcessObservation {
        val process = ProcessHandle.of(pid).orElse(null) ?: return LifecycleProcessObservation.Absent
        return observe(process)
    }

    fun observe(process: ProcessHandle): LifecycleProcessObservation {
        if (!process.isAlive) return LifecycleProcessObservation.Absent
        val pid = process.pid()
        val info = process.info()
        val start = info.startInstant().orElse(null) ?: return LifecycleProcessObservation.Unavailable
        val command = info.command().orElse(null) ?: return LifecycleProcessObservation.Unavailable
        val arguments = info.arguments().orElse(null) ?: return LifecycleProcessObservation.Unavailable
        return LifecycleProcessObservation.Present(
            LifecycleProcessSnapshot(pid, start.toEpochMilli(), command, arguments.toList())
        )
    }

    override fun retire(
        process: OwnedLifecycleProcess,
        operation: LifecycleOperation,
        allowance: Duration,
    ): LifecycleEffect {
        when (val current = observe(process.snapshot.pid)) {
            LifecycleProcessObservation.Absent -> return LifecycleEffect.Completed
            LifecycleProcessObservation.Unavailable ->
                return LifecycleEffect.Rejected(LifecycleFailure.REQUEST_OWNERSHIP_UNPROVEN)
            is LifecycleProcessObservation.Present -> {
                if (current.snapshot.startEpochMillis != process.snapshot.startEpochMillis)
                    return LifecycleEffect.Completed
                if (current.snapshot != process.snapshot)
                    return LifecycleEffect.Rejected(LifecycleFailure.REQUEST_OWNERSHIP_UNPROVEN)
            }
        }
        val handle = ProcessHandle.of(process.snapshot.pid).orElse(null) ?: return LifecycleEffect.Completed
        return try {
            if (operation == LifecycleOperation.STOP_FORCE) handle.destroyForcibly()
            handle.onExit().get(allowance.toMillis(), TimeUnit.MILLISECONDS)
            LifecycleEffect.Completed
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            LifecycleEffect.Rejected(LifecycleFailure.REQUESTS_DID_NOT_RETIRE)
        } catch (_: Exception) {
            LifecycleEffect.Rejected(LifecycleFailure.REQUESTS_DID_NOT_RETIRE)
        }
    }

    override fun selectedHost(executable: Path): LifecycleEffect =
        try {
            val user =
                ProcessHandle.current().info().user().orElse(null)
                    ?: return LifecycleEffect.Rejected(LifecycleFailure.HOST_OBSERVATION_REJECTED)
            ProcessHandle.allProcesses().use { processes ->
                val observed = processes.limit(MAXIMUM_HOST_PROCESS_OBSERVATIONS + 1).toList()
                if (observed.size > MAXIMUM_HOST_PROCESS_OBSERVATIONS)
                    LifecycleEffect.Rejected(LifecycleFailure.HOST_OBSERVATION_REJECTED)
                else inspectHost(executable, user, observed)
            }
        } catch (_: Exception) {
            LifecycleEffect.Rejected(LifecycleFailure.HOST_OBSERVATION_REJECTED)
        }

    private fun inspectHost(executable: Path, user: String, observed: List<ProcessHandle>): LifecycleEffect {
        val alive = observed.filter { it.isAlive }
        if (alive.any { it.info().command().orElse("") == executable.toString() })
            return LifecycleEffect.Rejected(LifecycleFailure.HOST_RESTART_REQUIRED)
        if (alive.any { it.info().user().orElse(null) == user && it.info().command().isEmpty })
            return LifecycleEffect.Rejected(LifecycleFailure.HOST_OBSERVATION_REJECTED)
        return LifecycleEffect.Completed
    }
}
