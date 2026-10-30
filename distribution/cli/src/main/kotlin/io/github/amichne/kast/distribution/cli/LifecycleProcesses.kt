package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstalledToolInvocation
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
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

/** An admitted record and exact JVM or native query process incarnation. */
internal sealed class OwnedLifecycleProcess private constructor(val snapshot: LifecycleProcessSnapshot) {
    private class Jvm(snapshot: LifecycleProcessSnapshot) : OwnedLifecycleProcess(snapshot) {
        override fun payloadUnchanged(): Boolean = true
    }

    private class NativeQuery(
        snapshot: LifecycleProcessSnapshot,
        private val installation: Path,
        private val manifest: BundledManifest,
        private val attributes: BasicFileAttributes,
    ) : OwnedLifecycleProcess(snapshot) {
        override fun payloadUnchanged(): Boolean =
            try {
                val executable = installation.resolve(NATIVE_QUERY_EXECUTABLE)
                sameExecutable(attributes, executable) &&
                    Files.isExecutable(executable) &&
                    payloadOwned(installation, manifest, NATIVE_QUERY_EXECUTABLE) &&
                    sameExecutable(attributes, executable)
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            }
    }

    protected abstract fun payloadUnchanged(): Boolean

    fun revalidate(observation: LifecycleProcessObservation): ShutdownProcessAdmission =
        when (observation) {
            LifecycleProcessObservation.Absent -> ShutdownProcessAdmission.Finished
            LifecycleProcessObservation.Unavailable -> ShutdownProcessAdmission.Rejected
            is LifecycleProcessObservation.Present ->
                when {
                    observation.snapshot.pid != snapshot.pid -> ShutdownProcessAdmission.Rejected
                    observation.snapshot.startEpochMillis != snapshot.startEpochMillis ->
                        ShutdownProcessAdmission.Finished
                    observation.snapshot != snapshot || !payloadUnchanged() -> ShutdownProcessAdmission.Rejected
                    else -> ShutdownProcessAdmission.Owned(this)
                }
        }

    companion object {
        /** Pure JVM matching retained for callers that have not yet admitted payload files. */
        fun admit(
            record: InstalledToolInvocation,
            observation: LifecycleProcessObservation,
            installation: Path,
        ): ShutdownProcessAdmission =
            admitIncarnation(record, observation) { snapshot ->
                if (ownsJvm(snapshot, installation)) ShutdownProcessAdmission.Owned(Jvm(snapshot))
                else ShutdownProcessAdmission.Rejected
            }

        /** Both ordinary status and retirement use this manifest-qualified admission. */
        fun admit(
            record: InstalledToolInvocation,
            observation: LifecycleProcessObservation,
            installation: Path,
            manifest: BundledManifest,
        ): ShutdownProcessAdmission =
            admitIncarnation(record, observation) { snapshot ->
                try {
                    if (ownsJvm(snapshot, installation)) {
                        val classpath = snapshot.arguments.indexOfFirst { it == "-classpath" || it == "-cp" }
                        if (
                            snapshot.arguments[classpath + 1].split(':').all {
                                payloadOwned(installation, manifest, "lib/" + Path.of(it).fileName)
                            }
                        )
                            ShutdownProcessAdmission.Owned(Jvm(snapshot))
                        else ShutdownProcessAdmission.Rejected
                    } else admitNative(snapshot, installation, manifest)
                } catch (_: IOException) {
                    ShutdownProcessAdmission.Rejected
                } catch (_: SecurityException) {
                    ShutdownProcessAdmission.Rejected
                }
            }

        private fun admitIncarnation(
            record: InstalledToolInvocation,
            observation: LifecycleProcessObservation,
            ownership: (LifecycleProcessSnapshot) -> ShutdownProcessAdmission,
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
                    else ownership(snapshot)
                }
            }
        }

        private fun admitNative(
            snapshot: LifecycleProcessSnapshot,
            installation: Path,
            manifest: BundledManifest,
        ): ShutdownProcessAdmission {
            val executable = installation.resolve(NATIVE_QUERY_EXECUTABLE)
            if (snapshot.command != executable.toString()) return ShutdownProcessAdmission.Rejected
            val attributes = Files.readAttributes(executable, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            val process = NativeQuery(snapshot, installation, manifest, attributes)
            return process.revalidate(LifecycleProcessObservation.Present(snapshot))
        }

        private fun sameExecutable(expected: BasicFileAttributes, path: Path): Boolean {
            val current = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            return expected.isRegularFile &&
                current.isRegularFile &&
                expected.fileKey() != null &&
                current.fileKey() == expected.fileKey() &&
                current.creationTime() == expected.creationTime() &&
                current.lastModifiedTime() == expected.lastModifiedTime() &&
                current.size() == expected.size()
        }

        private fun ownsJvm(snapshot: LifecycleProcessSnapshot, installation: Path): Boolean =
            try {
                Path.of(snapshot.command).fileName.toString() == "java" &&
                    ownsArguments(snapshot.arguments, installation)
            } catch (_: IllegalArgumentException) {
                false
            }

        private fun ownsArguments(arguments: List<String>, installation: Path): Boolean {
            val main = arguments.indexOfFirst {
                it in
                    setOf(
                        "io.github.amichne.kast.cli.rpc.KastToolRpcMain",
                        "io.github.amichne.kast.cli.publicshell.KastPublicCliMain",
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

private const val NATIVE_QUERY_EXECUTABLE = "bin/kast-query"

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
        val handle = ProcessHandle.of(process.snapshot.pid).orElse(null) ?: return LifecycleEffect.Completed
        when (process.revalidate(observe(handle))) {
            ShutdownProcessAdmission.Finished -> return LifecycleEffect.Completed
            ShutdownProcessAdmission.Rejected ->
                return LifecycleEffect.Rejected(LifecycleFailure.REQUEST_OWNERSHIP_UNPROVEN)
            is ShutdownProcessAdmission.Owned -> Unit
        }
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
