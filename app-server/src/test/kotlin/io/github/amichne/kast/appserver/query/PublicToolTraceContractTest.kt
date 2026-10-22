@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryDirectoryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourcePolicyDocument
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
        val encoded = Json.encodeToJsonElement(PublicToolTerminalStep.serializer(), step)
        assertEquals(Json.encodeToJsonElement(serializer<TraceShape>(), TraceShape("TRACE")), encoded)
        val reference = ProtocolText.parse("NON_ISSUED_SCHEMA_TEST_ONLY").refined()
        for ((scope, expected) in
            listOf(
                null to QueryExpansionScopeDocument.Workspace,
                PublicToolImpactWorkspaceDomain to QueryExpansionScopeDocument.Workspace,
                PublicToolImpactRetainedDomain to QueryExpansionScopeDocument.RetainedSeed,
            )) {
            val input =
                PublicToolQuerySymbols(
                    PublicToolRunAction(
                        PublicToolReferenceSource(BoundedProtocolList.create(listOf(reference)).refined()),
                        terminalStep = PublicToolTrace(scope),
                    )
                )
            val admitted =
                PublicToolContract.admit(
                        PublicToolIdentity.QUERY_SYMBOLS,
                        Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input),
                    )
                    .refined()
            val request = (admitted.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
            assertEquals(listOf(QueryStepDocument.Trace(expected)), request.steps.values)
        }
    }

    @Test
    fun `trace source domain is retained through public admission`() {
        val sourceSets =
            BoundedProtocolList.create(listOf("integrationTest", "main").map { ProtocolText.parse(it).refined() })
                .refined()
        val directory = ProtocolText.parse("services/orders").refined()
        val scope =
            PublicToolImpactSourceDomain(
                sourceSets = sourceSets,
                directory = directory,
                includeSubdirectories = false,
                sourcePolicy = PublicToolSourcePolicy.TEST_ONLY,
                generatedSources = PublicToolGeneratedSources.INCLUDE,
            )
        val step = PublicToolTrace(scope)
        val input =
            PublicToolQuerySymbols(
                PublicToolRunAction(
                    PublicToolAllSource(),
                    terminalStep = step,
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
        assertEquals(
            QueryExpansionScopeDocument.Sources(
                sourceSets,
                QueryDirectoryScopeDocument(directory, QueryContainmentDocument.DIRECT),
                QueryDiscoverySourcePolicyDocument.TEST_ONLY,
                QueryDiscoveryInclusionPolicyDocument.INCLUDE,
            ),
            trace.expansionScope,
        )
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
                    RejectedTraceEnvelope(RejectedTraceRequest(terminalStep = step)),
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
    val terminalStep: RejectedTraceFixture,
)

@Serializable private data class RejectedTraceEnvelope(val request: RejectedTraceRequest)
