package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.CanonicalOperation
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WirePayloadDecodingTest {
    @Test
    fun `parser depth rejection preserves every established payload role`() {
        val marker = JsonPrimitive("payload-marker")
        val cases =
            listOf(
                WireBodyDocument.Request(marker) to WireValueRole.REQUEST,
                WireBodyDocument.Complete(result = marker) to WireValueRole.RESULT,
                WireBodyDocument.Qualified(result = marker, qualification = JsonNull) to WireValueRole.RESULT,
                WireBodyDocument.Rejected(marker) to WireValueRole.REJECTION,
            )
        for ((body, role) in cases) {
            val envelope =
                WireEnvelopeDocument(
                    CanonicalOperationWireBindings.queryRun.schema.value,
                    CanonicalOperation.QUERY_RUN.id.value,
                    body,
                )
            val parts = wireJson.encodeToString(WireEnvelopeDocument.serializer(), envelope).split(marker.toString())
            assertEquals(2, parts.size)
            // Deliberately excessive JSON nesting must be rejected before payload deserialization.
            val excessive = "[".repeat(1000) + "null" + "]".repeat(1000)
            assertEquals(
                WireEnvelopeAdmission.Rejected(WireFailure.InvalidPayload(role)),
                admitWireEnvelope(parts.first() + excessive + parts.last()),
            )
        }
    }
}
