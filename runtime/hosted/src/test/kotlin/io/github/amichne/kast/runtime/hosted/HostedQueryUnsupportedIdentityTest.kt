package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.protocol.wire.WireFailure
import io.github.amichne.kast.protocol.wire.WireRequestAdmission
import io.github.amichne.kast.protocol.wire.WireRequestEnvelope
import io.github.amichne.kast.protocol.wire.WireValueRole
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class HostedQueryUnsupportedIdentityTest {
    @Test
    fun `unsupported take and query fanout reject at public wire admission`() {
        val binding = CanonicalOperationWireBindings.queryRun
        val encoded =
            (binding.encodeRequest(queryIdentityRequest(RelationPagingFixture.live().exact)) as WireEncoding.Encoded)
                .document
        val malformed =
            listOf(
                encoded.replace("\"steps\":[]", "\"steps\":[{\"type\":\"take\",\"count\":1}]"),
                encoded.replace(
                    "\"steps\":[]",
                    "\"steps\":[{\"type\":\"related\",\"relation\":\"references\",\"fanout\":1}]",
                ),
            )
        malformed.forEach { document ->
            assertNotEquals(encoded, document)
            val envelope = WireRequestEnvelope.admit(document) as WireRequestAdmission.Admitted
            assertEquals(
                WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.REQUEST)),
                binding.decodeRequest(envelope.request),
            )
        }
    }
}
