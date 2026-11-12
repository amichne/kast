package io.github.amichne.kast.distribution.cli

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReleaseDocumentSourceTest {
    @Test
    fun `unsupported URL retains open stage and finite failure without payloads`() {
        assertUnavailable("unsupported://private.invalid/secret", ReleaseReadFailure.URL_REJECTED)
    }

    @Test
    fun `relative URI retains open stage and finite failure without payloads`() {
        assertUnavailable("relative/private-secret", ReleaseReadFailure.INVALID_ARGUMENT)
    }

    @Test
    fun `release assets follow secure cross-host and relative redirects without changing bytes`() {
        val origin = URI("https://github.com/amichne/kast/releases/download/preview/build-record.json")
        val asset = URI("https://release-assets.githubusercontent.com/asset?signature=private")
        val final = URI("https://release-assets.githubusercontent.com/final?signature=private")
        val replies =
            listOf(
                DocumentConnection(origin, 302, asset.toString()),
                DocumentConnection(asset, 307, "/final?signature=private"),
                DocumentConnection(final, 200, "", "record bytes\n"),
            )
        val pending = ArrayDeque(replies)
        val observations = mutableListOf<ReleaseReadObservation>()
        val result =
            readReleaseDocumentObserved(origin.toString(), observations::add) { requested ->
                pending.removeFirst().also { assertEquals(requested.toURL(), it.url) }
            }
        assertEquals(ReleaseDocumentRead.Read("record bytes\n"), result)
        assertTrue(pending.isEmpty())
        assertTrue(replies.all { it.disconnected })
        assertEquals(listOf(ReleaseReadObservation.Read(13)), observations)
    }

    @Test
    fun `redirect rejection never opens unsafe targets and does not leak signed locations`() {
        for (location in
            listOf(
                "http://assets.invalid/private",
                "file:///private",
                "https://user:secret@assets.invalid/private",
                "",
            )) {
            val reply = DocumentConnection(URI("https://github.com/record"), 302, location)
            val observations = mutableListOf<ReleaseReadObservation>()
            var opened = false
            val result =
                readReleaseDocumentObserved(reply.url.toString(), observations::add) {
                    check(!opened) { "unsafe redirect was followed" }
                    opened = true
                    reply
                }
            assertTrue(result is ReleaseDocumentRead.Unavailable)
            assertTrue(reply.disconnected)
            assertEquals(1, observations.size)
            assertTrue(managementJson.encodeToString(observations.single()).contains("REDIRECT_REJECTED"))
        }
    }

    @Test
    fun `redirect cycle is bounded and every opened connection closes`() {
        val origin = URI("https://github.com/cycle")
        val replies = mutableListOf<DocumentConnection>()
        val observations = mutableListOf<ReleaseReadObservation>()
        val result =
            readReleaseDocumentObserved(origin.toString(), observations::add) {
                check(replies.size < 5) { "redirect limit did not stop network access" }
                DocumentConnection(origin, 308, origin.toString()).also(replies::add)
            }
        assertTrue(result is ReleaseDocumentRead.Unavailable)
        assertEquals(5, replies.size)
        assertTrue(replies.all { it.disconnected })
        assertTrue(managementJson.encodeToString(observations.single()).contains("REDIRECT_LIMIT"))
    }

    private fun assertUnavailable(url: String, failure: ReleaseReadFailure) {
        val observations = mutableListOf<ReleaseReadObservation>()
        val expected = ReleaseReadObservation.Unavailable(ReleaseReadStage.OPEN, failure)
        assertEquals(ReleaseDocumentRead.Unavailable(expected), readReleaseDocumentObserved(url, observations::add))
        assertEquals(listOf(expected), observations)
        assertEquals(
            expected,
            managementJson.decodeFromString<ReleaseReadObservation>(
                managementJson.encodeToString<ReleaseReadObservation>(expected)
            ),
        )
    }
}

private class DocumentConnection(
    uri: URI,
    private val status: Int,
    private val location: String,
    private val body: String = "",
) : HttpURLConnection(uri.toURL()) {
    var disconnected = false
        private set

    override fun getResponseCode(): Int = status

    override fun getHeaderField(name: String): String? = if (name == "Location") location else null

    override fun getInputStream() = ByteArrayInputStream(body.toByteArray())

    override fun disconnect() {
        disconnected = true
    }

    override fun connect() = Unit

    override fun usingProxy(): Boolean = false
}
