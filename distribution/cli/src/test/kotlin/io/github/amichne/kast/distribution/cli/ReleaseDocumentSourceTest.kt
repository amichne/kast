package io.github.amichne.kast.distribution.cli

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReleaseDocumentSourceTest {
    @Test
    fun `actual HTTP absence is distinct from forbidden or gone responses`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(exchange.requestURI.path.removePrefix("/").toInt(), -1)
            exchange.close()
        }
        server.start()
        try {
            for ((status, failure) in
                listOf(
                    404 to ReleaseReadFailure.NOT_FOUND,
                    403 to ReleaseReadFailure.HTTP_REJECTED,
                    410 to ReleaseReadFailure.HTTP_REJECTED,
                )) {
                assertUnavailable("http://127.0.0.1:${server.address.port}/$status", failure, ReleaseReadStage.RESPONSE)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `unsupported URL retains open stage and finite failure without payloads`() {
        assertUnavailable("unsupported://private.invalid/secret", ReleaseReadFailure.URL_REJECTED)
    }

    @Test
    fun `relative URI retains open stage and finite failure without payloads`() {
        assertUnavailable("relative/private-secret", ReleaseReadFailure.INVALID_ARGUMENT)
    }

    private fun assertUnavailable(
        url: String,
        failure: ReleaseReadFailure,
        stage: ReleaseReadStage = ReleaseReadStage.OPEN,
    ) {
        val observations = mutableListOf<ReleaseReadObservation>()
        val expected = ReleaseReadObservation.Unavailable(stage, failure)
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
