@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class PublicToolTraceContractTest {
    @Test
    fun `public trace has a required closed discriminator and canonical scope`() {
        val step = PublicToolTrace()
        val encoded = Json.encodeToJsonElement(PublicToolStep.serializer(), step)
        assertEquals(Json.encodeToJsonElement(serializer<TraceShape>(), TraceShape("TRACE")), encoded)
        val reference = ProtocolText.parse("NON_ISSUED_SCHEMA_TEST_ONLY").refined()
        val input =
            PublicToolQuerySymbols(
                PublicToolRunAction(
                    PublicToolReferenceSource(BoundedProtocolList.create(listOf(reference)).refined()),
                    BoundedProtocolList.create(listOf<PublicToolStep>(step)).refined(),
                )
            )
        val admitted =
            PublicToolContract.admit(
                    PublicToolIdentity.QUERY_SYMBOLS,
                    Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input),
                )
                .refined()
        val request = (admitted.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
        assertEquals(listOf(QueryStepDocument.Trace(QueryExpansionScopeDocument.Workspace)), request.steps.values)
    }

    @Test
    fun `trace source domain is retained through public admission`() {
        val sourceSets = BoundedProtocolList.create(listOf(ProtocolText.parse("main").refined())).refined()
        val scope =
            PublicToolImpactSourceDomain(
                sourceSets = sourceSets,
                sourcePolicy = PublicToolSourcePolicy.PRODUCTION_ONLY,
                generatedSources = PublicToolGeneratedSources.EXCLUDE,
            )
        val step = PublicToolTrace(scope)
        val input =
            PublicToolQuerySymbols(
                PublicToolRunAction(
                    PublicToolAllSource(),
                    BoundedProtocolList.create(listOf<PublicToolStep>(step)).refined(),
                )
            )
        val admitted =
            PublicToolContract.admit(
                    PublicToolIdentity.QUERY_SYMBOLS,
                    Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input),
                )
                .refined()
        val request = (admitted.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
        val trace = assertInstanceOf(QueryStepDocument.Trace::class.java, request.steps.values.single())
        assertInstanceOf(QueryExpansionScopeDocument.Sources::class.java, trace.expansionScope)
    }

    @Test
    fun `trace rejects unknown discriminator and unbounded invented controls`() {
        val encoder = Json {
            explicitNulls = false
            encodeDefaults = true
        }
        for (step in
            listOf(RejectedTraceFixture("TRACE", 999), RejectedTraceFixture("TRACE_ALL"), RejectedTraceFixture())) {
            val input =
                encoder.encodeToJsonElement(
                    serializer<RejectedTraceEnvelope>(),
                    RejectedTraceEnvelope(RejectedTraceRequest(steps = listOf(step))),
                )
            assertInstanceOf(
                Refinement.Rejected::class.java,
                PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, input),
            )
        }
    }
}

private fun <V, F> Refinement<V, F>.refined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid fixture: $failure")
    }

@Serializable private data class TraceShape(val type: String)

@Serializable private data class RejectedTraceFixture(val type: String? = null, val maximumDepth: Int? = null)

@Serializable
private data class RejectedTraceRequest(
    val type: String = "RUN",
    val source: PublicToolSource = PublicToolAllSource(),
    val steps: List<RejectedTraceFixture>,
)

@Serializable private data class RejectedTraceEnvelope(val request: RejectedTraceRequest)
