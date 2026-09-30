package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.INSTALLATION_MANIFEST_SCHEMA_VERSION
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val MAXIMUM_ONE_SHOT_RECORDS = 256
private const val MAXIMUM_ONE_SHOT_RECORD_BYTES = 4096L
private const val MAXIMUM_INSTALL_MANIFEST_BYTES = 67_108_864L
private const val MAXIMUM_MARKER_BYTES = 64L
private const val ONE_SHOT_READ_DEADLINE_NANOS = 1_500_000_000L
private val oneShotJson = Json { ignoreUnknownKeys = true }

@Serializable private data class OneShotPayload(val path: String, val sha256: String)

@Serializable
private data class OneShotManifest(
    val schemaVersion: Int,
    val installationRoot: String,
    val payloadFiles: List<OneShotPayload>,
)

@Serializable private data class OneShotRecord(val schemaVersion: Int, val pid: Long, val startEpochMillis: Long)

private sealed interface MarkerQualification {
    data object Qualified : MarkerQualification

    data object Unavailable : MarkerQualification
}

private sealed interface InvocationState {
    data object Active : InvocationState

    data object Finished : InvocationState

    data object Unavailable : InvocationState
}

private fun qualifyMarker(installation: Path): MarkerQualification {
    val marker = installation.resolve("share/kast/one-shot-observation-v1")
    val manifestRaw =
        readBoundedFile(installation.resolve("installation.json"), MAXIMUM_INSTALL_MANIFEST_BYTES)
            ?: return MarkerQualification.Unavailable
    val manifest =
        try {
            oneShotJson.decodeFromString<OneShotManifest>(manifestRaw)
        } catch (_: SerializationException) {
            return MarkerQualification.Unavailable
        }
    val markerEntries = manifest.payloadFiles.filter { it.path == "share/kast/one-shot-observation-v1" }
    if (
        manifest.schemaVersion != INSTALLATION_MANIFEST_SCHEMA_VERSION ||
            manifest.installationRoot != installation.toString()
    )
        return MarkerQualification.Unavailable
    if (markerEntries.size != 1 || readBoundedFile(marker, MAXIMUM_MARKER_BYTES) != "1\n")
        return MarkerQualification.Unavailable
    val digest =
        try {
            sha256(marker)
        } catch (_: Exception) {
            return MarkerQualification.Unavailable
        }
    if (markerEntries.single().sha256 != "sha256:$digest") return MarkerQualification.Unavailable
    return MarkerQualification.Qualified
}

private fun inspectInvocation(entry: Path): InvocationState {
    val raw = readBoundedFile(entry, MAXIMUM_ONE_SHOT_RECORD_BYTES) ?: return InvocationState.Unavailable
    val record =
        try {
            oneShotJson.decodeFromString<OneShotRecord>(raw)
        } catch (_: SerializationException) {
            return InvocationState.Unavailable
        }
    if (record.schemaVersion != 1 || record.pid <= 0 || record.startEpochMillis <= 0) return InvocationState.Unavailable
    val live = ProcessHandle.of(record.pid)
    if (live.isEmpty) return InvocationState.Finished
    val start = live.get().info().startInstant().orElse(null) ?: return InvocationState.Unavailable
    return if (start.toEpochMilli() == record.startEpochMillis) InvocationState.Active else InvocationState.Finished
}

/** A one-shot invocation is counted only while its exact process incarnation is live. */
internal fun observeOneShotRequests(installation: Path): Observation<Int> {
    val unavailable = Observation.unavailable<Int>("one_shot_observation_unavailable")
    if (qualifyMarker(installation) != MarkerQualification.Qualified) return unavailable
    val directory = installation.resolve("state/run/one-shot")
    if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return Observation.verified(0)
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return unavailable
    return try {
        if (directory.toRealPath() != directory) return unavailable
        countInvocations(directory)
    } catch (_: Exception) {
        unavailable
    }
}

private fun countInvocations(directory: Path): Observation<Int> {
    val unavailable = Observation.unavailable<Int>("one_shot_observation_unavailable")
    val started = System.nanoTime()
    var count = 0
    Files.newDirectoryStream(directory).use { entries ->
        var seen = 0
        for (entry in entries) {
            seen++
            if (System.nanoTime() - started > ONE_SHOT_READ_DEADLINE_NANOS) return unavailable
            if (seen > MAXIMUM_ONE_SHOT_RECORDS || !entry.fileName.toString().endsWith(".json")) return unavailable
            when (inspectInvocation(entry)) {
                InvocationState.Active -> count++
                InvocationState.Finished -> Unit
                InvocationState.Unavailable -> return unavailable
            }
        }
    }
    return Observation.verified(count)
}
