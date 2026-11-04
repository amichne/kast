package io.github.amichne.kast.cli.publicshell

import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Tag("public-launcher")
class NativeReleaseCatalogTest {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun `native versions retains a real connection rejection`(@TempDir directory: Path) {
        // A TCP listener bound to port zero receives a nonzero ephemeral port; port zero stays unserved.
        val result = runReleaseVersions(directory, "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=0")
        assertRejected(result, "CATALOG_UNAVAILABLE")
        assertEquals(listOf(unavailable("RESPONSE", "IO_FAILURE")), observations(result))
    }

    @Test
    fun `native versions reads both qualified HTTPS documents`(@TempDir directory: Path) {
        ReleaseHttpsFixture(directory, listOf(stable(), catalog())).use { peer ->
            val result = peer.versions()
            assertEquals(0, result.exit, result.output + result.errors)
            val document = json.decodeFromString<VersionsDocument>(result.output)
            assertEquals("LISTED", document.type)
            assertEquals("1.2.3", document.stable)
            assertEquals(listOf(MainEntry()), document.main)
            assertEquals(listOf(read(stable()), read(catalog())), observations(result))
        }
    }

    @Test
    fun `native versions rejects untrusted TLS before an HTTP read`(@TempDir directory: Path) {
        ReleaseHttpsFixture(directory, listOf(stable()), trusted = false).use { peer ->
            val result = peer.versions()
            assertRejected(result, "CATALOG_UNAVAILABLE")
            assertEquals(listOf(unavailable("RESPONSE", "TLS_FAILURE")), observations(result))
        }
    }

    @Test
    fun `native versions rejects redirects without following them`(@TempDir directory: Path) {
        ReleaseHttpsFixture(directory, listOf(stable().copy(status = 302))).use { peer ->
            val result = peer.versions()
            assertRejected(result, "CATALOG_UNAVAILABLE")
            assertEquals(listOf(unavailable("RESPONSE", "HTTP_REJECTED")), observations(result))
        }
    }

    @Test
    fun `native versions rejects a failed catalog response after stable discovery`(@TempDir directory: Path) {
        ReleaseHttpsFixture(directory, listOf(stable(), catalog().copy(status = 503))).use { peer ->
            val result = peer.versions()
            assertRejected(result, "CATALOG_UNAVAILABLE")
            assertEquals(listOf(read(stable()), unavailable("RESPONSE", "HTTP_REJECTED")), observations(result))
        }
    }

    @Test
    fun `native versions rejects an oversized HTTPS document`(@TempDir directory: Path) {
        val large = json.encodeToString(StableDocument(body = "x".repeat(65_536)))
        ReleaseHttpsFixture(directory, listOf(stable().copy(body = large))).use { peer ->
            val result = peer.versions()
            assertRejected(result, "CATALOG_UNAVAILABLE")
            assertEquals(listOf(unavailable("BODY", "DOCUMENT_TOO_LARGE")), observations(result))
        }
    }

    @Test
    fun `native versions preserves schema rejection after successful HTTPS`(@TempDir directory: Path) {
        ReleaseHttpsFixture(directory, listOf(stable().copy(body = "{"))).use { peer ->
            val result = peer.versions()
            assertRejected(result, "ARTIFACT_INVALID")
            assertEquals(listOf(TransportObservation("READ", "BODY", bytes = 1)), observations(result))
        }
    }

    private fun observations(result: NativeReleaseResult) =
        result.errors
            .lineSequence()
            .filter(String::isNotBlank)
            .map { json.decodeFromString<TransportObservation>(it) }
            .toList()

    private fun unavailable(stage: String, failure: String) =
        TransportObservation("UNAVAILABLE", stage, failure = failure)

    private fun read(reply: ReleaseHttpReply) =
        TransportObservation("READ", "BODY", bytes = reply.body.toByteArray().size)

    private fun assertRejected(result: NativeReleaseResult, reason: String) {
        assertEquals(1, result.exit, result.output + result.errors)
        assertTrue(result.output.isNotBlank(), result.errors)
        assertEquals(
            VersionsDocument("REJECTED", reason = reason),
            json.decodeFromString<VersionsDocument>(result.output),
        )
    }

    private fun stable() =
        ReleaseHttpReply("api.github.com", "/repos/amichne/kast/releases/latest", json.encodeToString(StableDocument()))

    private fun catalog() =
        ReleaseHttpReply(
            "raw.githubusercontent.com",
            "/amichne/kast/developer-latest/builds.json",
            json.encodeToString(CatalogDocument()),
        )
}

@Serializable
private data class StableDocument(
    @SerialName("tag_name") val tag: String = "v1.2.3",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val body: String = "",
)

@Serializable
private data class CatalogDocument(val schemaVersion: Int = 1, val builds: List<MainEntry> = listOf(MainEntry()))

@Serializable
private data class MainEntry(
    val mainRevision: String = "a".repeat(40),
    val buildRevision: String = "b".repeat(40),
    val sourceTree: String = "c".repeat(40),
    val releaseTag: String = "developer-v0.0.1",
    val version: String = "0.0.1",
    val platformTargets: List<String> = listOf("macos-aarch64"),
    val candidate: RecordReference = RecordReference("candidate.json", "d".repeat(64)),
    val promotion: RecordReference = RecordReference("promotion.json", "e".repeat(64)),
)

@Serializable private data class RecordReference(val name: String, val sha256: String)

@Serializable
private data class VersionsDocument(
    val type: String,
    val stable: String? = null,
    val main: List<MainEntry> = emptyList(),
    val reason: String? = null,
)

@Serializable
private data class TransportObservation(
    val type: String,
    val stage: String,
    val component: String = "kast-release-document",
    val bytes: Int? = null,
    val failure: String? = null,
)
