@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionModeDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireEncoding
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedRunnerControlledConnectionTest {
    @Test
    fun `only runner-controlled dispatch exceeds the connection deadline`() = runTest {
        for (mode in QueryExecutionModeDocument.entries) {
            val input = requestInput(mode)
            val output = ByteArrayOutputStream()
            val outcomes = mutableListOf<HostedEndpointOutcome>()
            val limits = connectionLimits()
            val start = testScheduler.currentTime
            serveHostedConnection(
                ByteArrayInputStream(input),
                output,
                HostedEndpointObserver { stage, outcome ->
                    if (stage == HostedEndpointStage.REQUEST) outcomes += outcome
                },
                limits,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            ) {
                delay(20_000L)
                HostedResponse.Completed("response")
            }
            when (mode) {
                QueryExecutionModeDocument.BOUNDED -> {
                    assertEquals(5_000L, testScheduler.currentTime - start)
                    assertEquals(listOf(HostedEndpointOutcome.STARTED, HostedEndpointOutcome.REJECTED), outcomes)
                    assertEquals(0, output.size())
                }
                QueryExecutionModeDocument.RUNNER_CONTROLLED -> {
                    assertEquals(20_000L, testScheduler.currentTime - start)
                    assertEquals(listOf(HostedEndpointOutcome.STARTED, HostedEndpointOutcome.COMPLETED), outcomes)
                    assertTrue(output.size() > 0)
                }
            }
        }
    }

    private fun requestInput(mode: QueryExecutionModeDocument): ByteArray {
        val request =
            QueryRunRequest.Run(
                QueryFromDocument.References(
                    bounded(listOf(QueryReferenceDocument.ExactSymbol(proven(ProtocolText.parse("exact:v3:fixture")))))
                ),
                bounded(emptyList()),
                QueryOutputDocument.Symbols(bounded(emptyList())),
                QueryExecutionDocument(
                    QueryExecutionKindDocument.EXHAUSTIVE,
                    QueryExecutionBudgetDocument.INTERACTIVE,
                ),
                executionMode = mode,
            )
        val encoded = (CanonicalOperationWireBindings.queryRun.encodeRequest(request) as WireEncoding.Encoded).document
        val input = ByteArrayOutputStream()
        HostedFrames.write(input, Json.encodeToString(QueryEnvelope("QUERY_RUN", "/workspace", encoded)))
        return input.toByteArray()
    }

    private fun connectionLimits(): ReadLimits =
        proven(
            ReadLimits.resolve(
                mapOf(
                    "KAST_READ_HOST_QUERY_MILLIS" to "4000",
                    "KAST_READ_SEMANTIC_MILLIS" to "3000",
                    "KAST_READ_HOST_CONNECTION_MILLIS" to "5000",
                    "KAST_READ_CLIENT_EXCHANGE_MILLIS" to "6000",
                )
            )
        )

    @Serializable private data class QueryEnvelope(val type: String, val root: String, val document: String)

    private fun <Value> proven(value: Refinement<Value, *>): Value =
        when (value) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> error("Invalid fixture: ${value.failure}")
        }

    private fun <Value> bounded(values: List<Value>) = proven(BoundedProtocolList.create(values))
}
