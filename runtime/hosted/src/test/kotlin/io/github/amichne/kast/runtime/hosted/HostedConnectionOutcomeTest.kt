package io.github.amichne.kast.runtime.hosted

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedConnectionOutcomeTest {
    @Test
    fun `a dispatched rejection never reports request completion`() = runTest {
        val input = ByteArrayOutputStream()
        HostedFrames.write(input, Json.encodeToString(DescribeRequest("DESCRIBE", Path.of(".").toRealPath().toString())))
        val observed = mutableListOf<Pair<HostedEndpointStage, HostedEndpointOutcome>>()
        serveHostedConnection(
            ByteArrayInputStream(input.toByteArray()),
            ByteArrayOutputStream(),
            HostedEndpointObserver { stage, outcome -> observed += stage to outcome },
        ) { HostedRequests.rejected(HostedEndpointFailure.WRONG_ROOT) }
        assertEquals(
            listOf(
                HostedEndpointStage.REQUEST to HostedEndpointOutcome.STARTED,
                HostedEndpointStage.REQUEST to HostedEndpointOutcome.REJECTED,
            ),
            observed,
        )
    }

    @Serializable private data class DescribeRequest(val type: String, val root: String)
}
