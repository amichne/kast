package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.TraversalExpansionRemainderDocument
import io.github.amichne.kast.protocol.contract.TraversalPartialExpansionDocument
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class TraversalPartialExpansionWireTest {
    @Test
    fun `wire retains page subject depth finite limitations and remainder with independent shape`() {
        for (remainder in TraversalExpansionRemainderDocument.entries) {
            val partial =
                TraversalPartialExpansionDocument.create(
                        ProtocolText.parse("exact:fixture-node").value(),
                        TraversalDepthDocument.parse(2).value(),
                        listOf(RelationLimitationDocument.RESULT_LIMIT_REACHED),
                        remainder,
                    )
                    .value()
            val result =
                TraversalRunResult(
                    ProtocolText.parse("/workspace").value(),
                    BoundedProtocolList.create(
                            emptyList<io.github.amichne.kast.protocol.contract.TraversalRecordDocument>()
                        )
                        .value(),
                    partialExpansions = BoundedProtocolList.create(listOf(partial)).value(),
                )
            val codec = CanonicalSymbolSerializers.traversalResult
            val encoded =
                assertInstanceOf(WireValueEncoding.Encoded::class.java, codec.encode(result, WireValueRole.RESULT))
            val expected =
                Json.parseToJsonElement(
                    checkNotNull(javaClass.getResource("/traversal/partial-expansion.json"))
                        .readText()
                        .replace("not_explored", remainder.name.lowercase())
                )
            assertEquals(expected, encoded.value.jsonObject.getValue("partialExpansions").jsonArray.single())
            assertEquals(WireDecoding.Decoded(result), codec.decode(encoded.value, WireValueRole.RESULT))
            val unknown =
                Json.parseToJsonElement(encoded.value.toString().replace(remainder.name.lowercase(), "unknown"))
            assertInstanceOf(WireDecoding.Rejected::class.java, codec.decode(unknown, WireValueRole.RESULT))
        }
    }

    private fun <T, F> Refinement<T, F>.value(): T = (this as Refinement.Refined).value
}
