package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedConnectionOutcomeTest {
    @Test
    fun `a dispatched rejection never reports request completion`() = runBlocking {
        val input = ByteArrayOutputStream()
        HostedFrames.write(
            input,
            Json.encodeToString(DescribeRequest("DESCRIBE", Path.of(".").toRealPath().toString())),
        )
        val observed = mutableListOf<Pair<HostedEndpointStage, HostedEndpointOutcome>>()
        var dispatched = 0
        serveHostedConnection(
            ByteArrayInputStream(input.toByteArray()),
            ByteArrayOutputStream(),
            HostedEndpointObserver { stage, outcome -> observed += stage to outcome },
        ) {
            dispatched++
            HostedResponse.Rejected(HostedEndpointFailure.WRONG_ROOT)
        }
        assertEquals(1, dispatched)
        assertEquals(
            listOf(
                HostedEndpointStage.REQUEST to HostedEndpointOutcome.STARTED,
                HostedEndpointStage.REQUEST to HostedEndpointOutcome.REJECTED,
            ),
            observed,
        )
    }

    private fun <Value> refined(value: Refinement<Value, *>): Value =
        when (value) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> error("Invalid test fixture")
        }

    @Serializable private data class DescribeRequest(val type: String, val root: String)
}
