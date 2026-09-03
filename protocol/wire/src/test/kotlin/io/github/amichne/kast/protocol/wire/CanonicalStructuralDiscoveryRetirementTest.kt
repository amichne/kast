package io.github.amichne.kast.protocol.wire

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanonicalStructuralDiscoveryRetirementTest {
    @Test
    fun `retired structure discriminator is rejected at the strict wire boundary`() {
        val document = wireJson.parseToJsonElement(
            """{"target":{"type":"structure","file":"src/A.kt"},"limit":10}""",
        )

        assertEquals(
            WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.REQUEST)),
            CanonicalReadSerializers.symbolDiscoverRequest.decode(document, WireValueRole.REQUEST),
        )
    }
}
