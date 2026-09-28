package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.TraversalStrategyDocument
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PublicToolWalkContractTest {
    @Test
    fun `omitted nullable and explicit controls lower to the same effective query`() {
        val reference = (ProtocolText.parse("NON_ISSUED_SCHEMA_TEST_ONLY") as Refinement.Refined).value
        val source =
            PublicToolReferenceSource((BoundedProtocolList.create(listOf(reference)) as Refinement.Refined).value)
        val depth = (ProtocolCount.parse(1) as Refinement.Refined).value
        val step = PublicToolWalk(PublicToolRelation.CALLERS, depth, PublicToolBoundedFanOutStrategy(32))
        val steps = (BoundedProtocolList.create(listOf<PublicToolStep>(step)) as Refinement.Refined).value
        val input = PublicToolQuerySymbols(PublicToolRunAction(source, steps))
        val omitted = Json {
            explicitNulls = false
            encodeDefaults = false
        }
        val explicit = Json {
            explicitNulls = true
            encodeDefaults = true
        }
        val requests =
            listOf(omitted, explicit).map { codec ->
                val document = codec.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input)
                val admitted =
                    PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, document) as Refinement.Refined
                (admitted.value.canonical as PublicToolCanonical.Query).request
            }
        assertEquals(requests.first(), requests.last())
        val walk = ((requests.first() as QueryRunRequest.Run).steps.values.single() as QueryStepDocument.Walk)
        assertEquals(depth, walk.maximumDepth)
        assertEquals(
            TraversalStrategyDocument.BoundedFanOut((ProtocolCount.parse(32) as Refinement.Refined).value),
            walk.strategy,
        )
    }

    @Test
    fun `walk depth and strategy lower without losing typed traversal output`() {
        val reference = (ProtocolText.parse("NON_ISSUED_SCHEMA_TEST_ONLY") as Refinement.Refined).value
        val source =
            PublicToolReferenceSource((BoundedProtocolList.create(listOf(reference)) as Refinement.Refined).value)
        val depth = (ProtocolCount.parse(3) as Refinement.Refined).value
        val fanOut = PublicToolBoundedFanOutStrategy(2)
        for ((provided, expected) in
            listOf(
                null to TraversalStrategyDocument.BreadthFirst,
                fanOut to TraversalStrategyDocument.BoundedFanOut((ProtocolCount.parse(2) as Refinement.Refined).value),
            )) {
            val steps =
                (BoundedProtocolList.create(
                        listOf<PublicToolStep>(PublicToolWalk(PublicToolRelation.CALLERS, depth, provided))
                    ) as Refinement.Refined)
                    .value
            val input = PublicToolQuerySymbols(PublicToolRunAction(source, steps, PublicToolTraversalRecordsOutput))
            val encoded = Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input)
            val request = encoded.jsonObject.getValue("request").jsonObject
            val walk = request.getValue("steps").jsonArray.single().jsonObject
            assertEquals("WALK", walk.getValue("type").jsonPrimitive.content)
            assertEquals(3, walk.getValue("maximumDepth").jsonPrimitive.int)
            assertEquals(
                "TRAVERSAL_RECORDS",
                request.getValue("output").jsonObject.getValue("type").jsonPrimitive.content,
            )
            if (provided == fanOut) {
                val strategy = walk.getValue("strategy").jsonObject
                assertEquals("BOUNDED_FAN_OUT", strategy.getValue("type").jsonPrimitive.content)
                assertEquals(2, strategy.getValue("maximumEdgesPerNode").jsonPrimitive.int)
            }
            val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, encoded) as Refinement.Refined
            val run = (admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
            val lowered = run.steps.values.single() as QueryStepDocument.Walk
            assertEquals(depth, lowered.maximumDepth)
            assertEquals(expected, lowered.strategy)
            assertEquals(QueryOutputDocument.TraversalRecords, run.output)
        }
    }
}
