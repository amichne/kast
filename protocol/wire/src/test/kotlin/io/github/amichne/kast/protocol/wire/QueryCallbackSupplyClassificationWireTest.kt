package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.QueryCallbackExclusionReasonDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackNamedUnavailableCauseDocument
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryCallbackSupplyClassificationWireTest {
    @Test
    fun `returned and unsupported flow preserve exact finite causes without activation claims`() {
        val cases =
            listOf(
                QueryCallbackFlowCauseDocument.RETURNED_CALLBACK to "callback-flow-returned.json",
                QueryCallbackFlowCauseDocument.UNSUPPORTED_CALLBACK_SUPPLY to "callback-flow-unsupported-supply.json",
            )
        for ((cause, resource) in cases) assertEquals(
            expected(resource),
            wireJson.encodeToJsonElement(
                QueryCallbackFlowWireDocument.serializer(),
                QueryCallbackFlowWireDocument.Unavailable(cause),
            ),
        )
        assertEquals(
            expected("callback-policy-unsupported-boundary.json"),
            wireJson.encodeToJsonElement(
                QueryCallbackNamedPolicyWireDocument.serializer(),
                QueryCallbackNamedPolicyWireDocument.Unavailable(
                    QueryCallbackNamedUnavailableCauseDocument.UNSUPPORTED_BOUNDARY
                ),
            ),
        )
        assertEquals(
            expected("callback-policy-returned.json"),
            wireJson.encodeToJsonElement(
                QueryCallbackNamedPolicyWireDocument.serializer(),
                QueryCallbackNamedPolicyWireDocument.Excluded(
                    QueryCallbackExclusionReasonDocument.RETURNED_CALLBACK,
                    RelationOccurrenceWireDocument("candidate:lambda", "Seed.kt", SourceRangeWireDocument(4, 18)),
                ),
            ),
        )
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(
                QueryCallbackFlowWireDocument.serializer(),
                expected("callback-flow-unproven-direct-activation.json").toString(),
            )
        }
    }

    @Test
    fun `returned and unsupported supply wire variants retain independent required shapes`() {
        val occurrence =
            RelationOccurrenceWireDocument("candidate:Boundary.kt:9:26", "Boundary.kt", SourceRangeWireDocument(9, 26))
        val cases =
            listOf(
                QueryCallbackBodySupplyWireDocument.Returned(occurrence) to "callback-body-supply-returned.json",
                QueryCallbackBodySupplyWireDocument.Unsupported to "callback-body-supply-unsupported.json",
            )
        for ((supply, resource) in cases) assertEquals(
            expected(resource),
            wireJson.encodeToJsonElement(QueryCallbackBodySupplyWireDocument.serializer(), supply),
        )
    }

    @Test
    fun `unsupported primary body preserves compiler identity and exact supply`() {
        val wire = QueryCallbackWireFixture().callbackDocument().toWireDocument()
        val flow = wire.flow as QueryCallbackFlowWireDocument.Observed
        val occurrence =
            RelationOccurrenceWireDocument("candidate:Seed.kt:3:19", "Seed.kt", SourceRangeWireDocument(3, 19))
        val cases =
            listOf(
                QueryCallbackBodySupplyWireDocument.Invocation(occurrence) to
                    QueryCallbackFlowCauseDocument.UNSUPPORTED_CALLBACK_SUPPLY,
                QueryCallbackBodySupplyWireDocument.Returned(occurrence) to
                    QueryCallbackFlowCauseDocument.RETURNED_CALLBACK,
                QueryCallbackBodySupplyWireDocument.Stored to QueryCallbackFlowCauseDocument.STORED_CALLBACK,
                QueryCallbackBodySupplyWireDocument.Unsupported to
                    QueryCallbackFlowCauseDocument.UNSUPPORTED_CALLBACK_SUPPLY,
            )
        for ((supply, cause) in cases) {
            val binding = QueryCallbackBindingWireDocument.Unavailable(cause)
            val obligations = listOf(QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION, cause)
            val owner = QueryCallbackBodyBindingWireDocument(flow.body, supply, binding, obligations)
            val qualified =
                wire.copy(
                    namedPolicy = policyFor(cause, flow.body.occurrence),
                    flow =
                        flow.copy(
                            binding = binding,
                            invocations = emptyList(),
                            obligations = obligations,
                            ownerBindings = listOf(owner),
                        ),
                )
            val encoded = wireJson.encodeToString(QueryCallbackObservationWireDocument.serializer(), qualified)
            val decoded = wireJson.decodeFromString(QueryCallbackObservationWireDocument.serializer(), encoded)
            assertTrue(decoded.toContract() is WireDocumentConversion.Converted)
            assertEquals(owner, (decoded.flow as QueryCallbackFlowWireDocument.Observed).ownerBindings.single())
        }
    }

    private fun policyFor(
        cause: QueryCallbackFlowCauseDocument,
        body: RelationOccurrenceWireDocument,
    ): QueryCallbackNamedPolicyWireDocument =
        when (cause) {
            QueryCallbackFlowCauseDocument.STORED_CALLBACK ->
                QueryCallbackNamedPolicyWireDocument.Excluded(
                    QueryCallbackExclusionReasonDocument.STORED_CALLBACK,
                    body,
                )
            QueryCallbackFlowCauseDocument.RETURNED_CALLBACK ->
                QueryCallbackNamedPolicyWireDocument.Excluded(
                    QueryCallbackExclusionReasonDocument.RETURNED_CALLBACK,
                    body,
                )
            QueryCallbackFlowCauseDocument.UNSUPPORTED_CALLBACK_SUPPLY ->
                QueryCallbackNamedPolicyWireDocument.Unavailable(
                    QueryCallbackNamedUnavailableCauseDocument.UNSUPPORTED_BOUNDARY
                )
            else -> error("Unsupported classification fixture")
        }

    private fun expected(name: String) =
        wireJson.parseToJsonElement(checkNotNull(javaClass.getResource("/query/$name")).readText())
}
