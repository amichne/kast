package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Serializable private data class OneShotTestPayload(val path: String, val sha256: String)

@Serializable
private data class OneShotTestManifest(
    val schemaVersion: Int,
    val installationRoot: String,
    val payloadFiles: List<OneShotTestPayload>,
)

@Serializable private data class OneShotTestRecord(val schemaVersion: Int, val pid: Long, val startEpochMillis: Long)

class OneShotObservationTest {
    @TempDir lateinit var installation: Path

    @Test
    fun `verified one-shot records count only the live process incarnation`() {
        val directory = qualifyFixture()
        assertEquals(Observation.verified(0), observeOneShotRequests(installation))
        Files.createDirectories(directory)
        val process = ProcessHandle.current()
        val start = process.info().startInstant().orElseThrow().toEpochMilli()
        Files.writeString(
            directory.resolve("active.json"),
            Json.encodeToString(OneShotTestRecord(1, process.pid(), start)),
        )
        Files.writeString(
            directory.resolve("former.json"),
            Json.encodeToString(OneShotTestRecord(1, process.pid(), start - 1)),
        )
        assertEquals(Observation.verified(1), observeOneShotRequests(installation))
        Files.writeString(
            directory.resolve("active.json"),
            Json.encodeToString(OneShotTestRecord(99, process.pid(), start)),
        )
        assertEquals(ObservationState.UNAVAILABLE, observeOneShotRequests(installation).state)
    }

    @Test
    fun `unqualified marker cannot claim verified empty requests`() {
        qualifyFixture()
        Files.writeString(installation.resolve("share/kast/one-shot-observation-v1"), "foreign")
        assertEquals(ObservationState.UNAVAILABLE, observeOneShotRequests(installation).state)
    }

    private fun qualifyFixture(): Path {
        installation = installation.toRealPath()
        val marker = installation.resolve("share/kast/one-shot-observation-v1")
        Files.createDirectories(marker.parent)
        Files.writeString(marker, "1\n")
        Files.writeString(
            installation.resolve("installation.json"),
            Json.encodeToString(
                OneShotTestManifest(
                    3,
                    installation.toString(),
                    listOf(OneShotTestPayload("share/kast/one-shot-observation-v1", "sha256:${sha256(marker)}")),
                )
            ),
        )
        return installation.resolve("state/run/one-shot")
    }
}
