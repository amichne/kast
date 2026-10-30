package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstalledToolInvocation
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val MAXIMUM_ONE_SHOT_RECORDS = 256
private const val MAXIMUM_ONE_SHOT_RECORD_BYTES = 4096L
private const val MAXIMUM_MARKER_BYTES = 64L
private const val ONE_SHOT_READ_DEADLINE_NANOS = 1_500_000_000L
private val oneShotJson = Json { ignoreUnknownKeys = true }

private fun inspectInvocation(
    entry: Path,
    installation: Path,
    manifest: BundledManifest,
    observe: (Long) -> LifecycleProcessObservation,
): ShutdownProcessAdmission {
    val raw = readBoundedFile(entry, MAXIMUM_ONE_SHOT_RECORD_BYTES) ?: return ShutdownProcessAdmission.Rejected
    val record =
        try {
            oneShotJson.decodeFromString<InstalledToolInvocation>(raw)
        } catch (_: SerializationException) {
            return ShutdownProcessAdmission.Rejected
        }
    if (record.schemaVersion != 1 || record.pid <= 0 || record.startEpochMillis <= 0)
        return ShutdownProcessAdmission.Rejected
    return OwnedLifecycleProcess.admit(record, observe(record.pid), installation, manifest)
}

/** A one-shot invocation needs the same process and payload proof as ordinary retirement. */
internal fun observeOneShotRequests(installation: Path): Observation<Int> =
    observeOneShotRequests(installation, NativeLifecycleProcesses::observe)

internal fun observeOneShotRequests(
    installation: Path,
    observe: (Long) -> LifecycleProcessObservation,
): Observation<Int> {
    val unavailable = Observation.unavailable<Int>("one_shot_observation_unavailable")
    return try {
        val manifest =
            when (val read = readBundledManifest(installation)) {
                is BundledManifestRead.Read -> read.manifest
                BundledManifestRead.Unavailable,
                BundledManifestRead.Invalid -> return unavailable
            }
        val marker = "share/kast/one-shot-observation-v1"
        if (
            !payloadOwned(installation, manifest, marker) ||
                readBoundedFile(installation.resolve(marker), MAXIMUM_MARKER_BYTES) != "1\n"
        )
            return unavailable
        val directory = installation.resolve("state/run/one-shot")
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return Observation.verified(0)
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || directory.toRealPath() != directory)
            return unavailable
        countInvocations(directory, installation, manifest, observe)
    } catch (_: Exception) {
        unavailable
    }
}

private fun countInvocations(
    directory: Path,
    installation: Path,
    manifest: BundledManifest,
    observe: (Long) -> LifecycleProcessObservation,
): Observation<Int> {
    val unavailable = Observation.unavailable<Int>("one_shot_observation_unavailable")
    val started = System.nanoTime()
    var count = 0
    Files.newDirectoryStream(directory).use { entries ->
        var seen = 0
        for (entry in entries) {
            seen++
            if (System.nanoTime() - started > ONE_SHOT_READ_DEADLINE_NANOS) return unavailable
            if (seen > MAXIMUM_ONE_SHOT_RECORDS || !entry.fileName.toString().endsWith(".json")) return unavailable
            when (inspectInvocation(entry, installation, manifest, observe)) {
                is ShutdownProcessAdmission.Owned -> count++
                ShutdownProcessAdmission.Finished -> Unit
                ShutdownProcessAdmission.Rejected -> return unavailable
            }
        }
    }
    return Observation.verified(count)
}
