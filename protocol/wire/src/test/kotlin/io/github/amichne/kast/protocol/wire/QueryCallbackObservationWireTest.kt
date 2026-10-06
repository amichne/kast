package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.QueryCallbackExclusionReasonDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowFailureDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackNamedUnavailableCauseDocument
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryCallbackObservationWireTest {
    @Test
    fun `named call policy encodes distinct inline excluded and unavailable proof`() {
        val cases =
            listOf(
                QueryCallbackNamedPolicyWireDocument.AdmittedInline to "callback-policy-inline.json",
                QueryCallbackNamedPolicyWireDocument.AdmittedDirect to "callback-policy-direct.json",
                QueryCallbackNamedPolicyWireDocument.Excluded(
                    QueryCallbackExclusionReasonDocument.NON_INLINE_ARGUMENT,
                    RelationOccurrenceWireDocument("candidate:lambda", "Seed.kt", SourceRangeWireDocument(4, 18)),
                ) to "callback-policy-excluded.json",
                QueryCallbackNamedPolicyWireDocument.Unavailable(
                    QueryCallbackNamedUnavailableCauseDocument.UNRESOLVED_ARGUMENT_MAPPING
                ) to "callback-policy-unavailable.json",
            )
        for ((policy, resource) in cases) assertEquals(
            expected(resource),
            wireJson.encodeToJsonElement(QueryCallbackNamedPolicyWireDocument.serializer(), policy),
        )
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(
                QueryCallbackNamedPolicyWireDocument.serializer(),
                expected("callback-policy-unknown.json").toString(),
            )
        }
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(
                QueryCallbackNamedPolicyWireDocument.serializer(),
                expected("callback-policy-extra-field.json").toString(),
            )
        }
    }

    @Test
    fun `callback invocation flow preserves precise unavailable and rejected conditions`() {
        assertEquals(
            expected("callback-flow-unavailable.json"),
            wireJson.encodeToJsonElement(
                QueryCallbackFlowWireDocument.serializer(),
                QueryCallbackFlowWireDocument.Unavailable(QueryCallbackFlowCauseDocument.STORED_CALLBACK),
            ),
        )
        assertEquals(
            expected("callback-flow-rejected.json"),
            wireJson.encodeToJsonElement(
                QueryCallbackFlowWireDocument.serializer(),
                QueryCallbackFlowWireDocument.ContractRejected(QueryCallbackFlowFailureDocument.BASIS_MISMATCH),
            ),
        )
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(
                QueryCallbackFlowWireDocument.serializer(),
                expected("callback-flow-null-body.json").toString(),
            )
        }
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(
                QueryCallbackFlowWireDocument.serializer(),
                expected("callback-flow-unknown-cause.json").toString(),
            )
        }
    }

    private fun expected(name: String) =
        wireJson.parseToJsonElement(checkNotNull(javaClass.getResource("/query/$name")).readText())
}
