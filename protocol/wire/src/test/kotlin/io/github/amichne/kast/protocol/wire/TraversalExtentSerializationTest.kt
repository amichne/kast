package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.TraversalExtentDocument
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TraversalExtentSerializationTest {
    @Test
    fun `extent variants encode independent closed shapes`() {
        val two = (ProtocolCount.parse(2) as Refinement.Refined).value
        val cases =
            listOf(
                TraversalExtentDocument.Exhaustive to fixture("extent-exhaustive"),
                TraversalExtentDocument.ThroughDepth(two) to fixture("extent-through-depth"),
            )
        for ((extent, shape) in cases) {
            assertEquals(
                Json.parseToJsonElement(shape),
                Json.encodeToJsonElement(TraversalExtentDocument.serializer(), extent),
            )
        }
    }

    @Test
    fun `unknown contradictory and invalid depth extent input rejects`() {
        for (shape in
            listOf(
                fixture("extent-unknown"),
                fixture("extent-contradictory"),
                fixture("extent-missing-depth"),
                fixture("extent-zero-depth"),
                fixture("extent-negative-depth"),
                fixture("extent-null-depth"),
            )) {
            assertThrows(SerializationException::class.java) {
                Json.decodeFromString(TraversalExtentDocument.serializer(), shape)
            }
        }
    }

    private fun fixture(name: String): String = checkNotNull(javaClass.getResource("/query/$name.json")).readText()
}
