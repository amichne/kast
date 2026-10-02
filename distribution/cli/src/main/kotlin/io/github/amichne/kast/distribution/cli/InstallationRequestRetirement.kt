package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstalledToolInvocation
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val MAXIMUM_REQUEST_RECORDS = 256
private const val REQUEST_RECORD_BYTES = 4096L
private const val REQUEST_RETIREMENT_SECONDS = 30L
private val requestRecordJson = Json { ignoreUnknownKeys = false }

internal class InstallationRequestRetirement(
    private val installation: Path,
    private val manifest: BundledManifest,
    private val processes: LifecycleProcesses,
) {
    fun retire(operation: LifecycleOperation): LifecycleEffect {
        if (!payloadOwned(installation, manifest, "share/kast/one-shot-observation-v1")) return ownershipRejected()
        val pending = mutableListOf<OwnedLifecycleProcess>()
        for (relative in listOf("state/run/one-shot", "state/run/tool-sessions")) {
            when (val admitted = admitDirectory(installation.resolve(relative))) {
                RequestDirectoryAdmission.Rejected -> return ownershipRejected()
                is RequestDirectoryAdmission.Admitted -> pending.addAll(admitted.processes)
            }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(REQUEST_RETIREMENT_SECONDS)
        for (process in pending) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return LifecycleEffect.Rejected(LifecycleFailure.REQUESTS_DID_NOT_RETIRE)
            when (val retired = processes.retire(process, operation, Duration.ofNanos(remaining))) {
                LifecycleEffect.Completed -> Unit
                is LifecycleEffect.Rejected -> return retired
            }
            when (val checked = verifyRetired(process)) {
                LifecycleEffect.Completed -> Unit
                is LifecycleEffect.Rejected -> return checked
            }
        }
        return LifecycleEffect.Completed
    }

    private fun admitDirectory(directory: Path): RequestDirectoryAdmission {
        if (Files.notExists(directory, NOFOLLOW_LINKS)) return RequestDirectoryAdmission.Admitted(emptyList())
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || directory.toRealPath() != directory)
            return RequestDirectoryAdmission.Rejected
        val pending = mutableListOf<OwnedLifecycleProcess>()
        Files.newDirectoryStream(directory).use { entries ->
            var count = 0
            for (entry in entries) {
                count++
                if (count > MAXIMUM_REQUEST_RECORDS || !entry.fileName.toString().endsWith(".json"))
                    return RequestDirectoryAdmission.Rejected
                when (val admitted = admitRecord(entry)) {
                    ShutdownProcessAdmission.Finished -> Unit
                    ShutdownProcessAdmission.Rejected -> return RequestDirectoryAdmission.Rejected
                    is ShutdownProcessAdmission.Owned -> pending.add(admitted.process)
                }
            }
        }
        return RequestDirectoryAdmission.Admitted(pending)
    }

    private fun admitRecord(entry: Path): ShutdownProcessAdmission {
        val raw = readBoundedFile(entry, REQUEST_RECORD_BYTES) ?: return ShutdownProcessAdmission.Rejected
        val record =
            try {
                requestRecordJson.decodeFromString<InstalledToolInvocation>(raw)
            } catch (_: SerializationException) {
                return ShutdownProcessAdmission.Rejected
            }
        if (record.schemaVersion != 1 || record.pid <= 0 || record.startEpochMillis <= 0)
            return ShutdownProcessAdmission.Rejected
        val admitted = OwnedLifecycleProcess.admit(record, processes.observe(record.pid), installation)
        if (admitted !is ShutdownProcessAdmission.Owned) return admitted
        val arguments = admitted.process.snapshot.arguments
        val classpath = arguments.indexOfFirst { it == "-classpath" || it == "-cp" }
        return if (
            arguments[classpath + 1].split(':').all {
                payloadOwned(installation, manifest, "lib/" + Path.of(it).fileName)
            }
        )
            admitted
        else ShutdownProcessAdmission.Rejected
    }

    private fun verifyRetired(process: OwnedLifecycleProcess): LifecycleEffect =
        when (val current = processes.observe(process.snapshot.pid)) {
            LifecycleProcessObservation.Absent -> LifecycleEffect.Completed
            LifecycleProcessObservation.Unavailable -> ownershipRejected()
            is LifecycleProcessObservation.Present ->
                if (current.snapshot.startEpochMillis == process.snapshot.startEpochMillis)
                    LifecycleEffect.Rejected(LifecycleFailure.REQUESTS_DID_NOT_RETIRE)
                else LifecycleEffect.Completed
        }

    private fun ownershipRejected() = LifecycleEffect.Rejected(LifecycleFailure.REQUEST_OWNERSHIP_UNPROVEN)
}

private sealed interface RequestDirectoryAdmission {
    data class Admitted(val processes: List<OwnedLifecycleProcess>) : RequestDirectoryAdmission

    data object Rejected : RequestDirectoryAdmission
}
