package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.SymbolDiscoverLimitation
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryDocument
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireFailure
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedEvaluationOutcome
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `canonical completion qualification and rejection retain their typed outcome and wire shape`() {
        val binding = CanonicalOperationWireBindings.symbolDiscover
        val evidence =
            EvidenceEnvelope(
                binding.operation.id,
                refined(EvidenceGeneration.parse(1)),
                SymbolDiscoverResult(refined(BoundedProtocolList.create(emptyList<SymbolDiscoveryDocument>()))),
            )
        val outcomes =
            listOf(
                OperationOutcome.Complete(evidence) to HostedEvaluationOutcome.COMPLETE,
                OperationOutcome.Qualified(
                    evidence,
                    refined(SymbolDiscoverQualification.from(setOf(SymbolDiscoverLimitation.WORK_LIMIT))),
                ) to HostedEvaluationOutcome.QUALIFIED,
                OperationOutcome.Rejected(SymbolDiscoverRejection.WORKSPACE_NOT_READY) to
                    HostedEvaluationOutcome.REJECTED,
            )
        for ((semantic, expected) in outcomes) {
            val response = HostedResponse.Canonical.encode(binding, semantic)
            assertEquals(expected, response.outcome)
            assertSame(semantic, (response as HostedResponse.Canonical<*, *, *>).semantic)
            val body =
                Json.parseToJsonElement(response.document)
                    .let { it as kotlinx.serialization.json.JsonObject }
                    .getValue("body")
                    .let { it as kotlinx.serialization.json.JsonObject }
            assertEquals(
                expected.name.lowercase(),
                (body.getValue("type") as kotlinx.serialization.json.JsonPrimitive).content,
            )
            val observer = mutableListOf<HostedEndpointOutcome>()
            HostedEndpointObserver { _, outcome -> observer += outcome }.responded(response)
            assertEquals(
                listOf(
                    when (expected) {
                        HostedEvaluationOutcome.COMPLETE -> HostedEndpointOutcome.COMPLETED
                        HostedEvaluationOutcome.QUALIFIED -> HostedEndpointOutcome.QUALIFIED
                        HostedEvaluationOutcome.REJECTED -> HostedEndpointOutcome.REJECTED
                        HostedEvaluationOutcome.EVALUATED -> error("No unspecified semantic result")
                    }
                ),
                observer,
            )
        }
    }

    @Test
    fun `encoding failure preserves the original semantic outcome and finite wire failure`() {
        val semantic =
            OperationOutcome.Complete(
                EvidenceEnvelope(
                    CanonicalOperation.SOURCE_READ.id,
                    refined(EvidenceGeneration.parse(1)),
                    SymbolDiscoverResult(refined(BoundedProtocolList.create(emptyList<SymbolDiscoveryDocument>()))),
                )
            )
        val response = HostedResponse.Canonical.encode(CanonicalOperationWireBindings.symbolDiscover, semantic)
        assertInstanceOf(HostedResponse.EncodingRejected::class.java, response)
        response as HostedResponse.EncodingRejected
        assertSame(semantic, response.semantic)
        assertEquals(
            WireFailure.UnexpectedOperation(CanonicalOperation.SYMBOL_DISCOVER, CanonicalOperation.SOURCE_READ),
            response.failure,
        )
        assertEquals(HostedEvaluationOutcome.REJECTED, response.outcome)
        assertTrue(response.document.contains("RESPONSE_REJECTED"))
    }

    private fun <Value> refined(value: Refinement<Value, *>): Value =
        when (value) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> error("Invalid test fixture")
        }

    @Serializable private data class DescribeRequest(val type: String, val root: String)
}
