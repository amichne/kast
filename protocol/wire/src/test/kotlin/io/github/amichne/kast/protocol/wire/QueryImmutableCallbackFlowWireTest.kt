package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument
import io.github.amichne.kast.protocol.contract.QueryImmutableCallbackFlowDocument
import io.github.amichne.kast.protocol.contract.QueryImmutableCallbackUseDocument
import io.github.amichne.kast.protocol.contract.QueryImmutableCallbackValueDocument
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryImmutableCallbackFlowWireTest {
    @Test
    fun `unused and direct uses encode exact source proof and closed scan`() {
        val value = QueryImmutableCallbackValueWireTest().fixture()
        val admitted = flowFixture()
        val direct = (admitted.uses.values.last() as QueryImmutableCallbackUseDocument.Direct).binding
        val wire = admitted.immutableFlowWire()
        val encoded = wireJson.encodeToJsonElement(QueryImmutableCallbackFlowWireDocument.serializer(), wire).jsonObject
        assertEquals(setOf("source_value", "uses", "obligations", "scan"), encoded.keys)
        assertEquals(
            listOf(JsonPrimitive("UNUSED"), JsonPrimitive("DIRECT")),
            encoded.getValue("uses").jsonArray.map { it.jsonObject.getValue("type") },
        )
        assertEquals(JsonPrimitive("EXHAUSTIVE"), encoded.getValue("scan"))
        assertEquals(WireDocumentConversion.Converted(admitted), wire.toContract())
        assertEquals(
            WireDocumentConversion.Rejected,
            wire.copy(scan = QueryCallbackInvocationScanDocument.INCOMPLETE).toContract(),
        )
        assertEquals(
            WireDocumentConversion.Rejected,
            wire
                .copy(
                    uses =
                        listOf(
                            QueryImmutableCallbackUseWireDocument.Direct(
                                value.immutableCallbackWire(),
                                direct.callbackWire(),
                            )
                        )
                )
                .toContract(),
        )
    }

    private fun flowFixture(): QueryImmutableCallbackFlowDocument {
        val value = QueryImmutableCallbackValueWireTest().fixture()
        val root =
            (QueryImmutableCallbackValueDocument.create(
                    value.origin,
                    value.source,
                    value.source,
                    bounded(emptyList()),
                    bounded(emptyList()),
                ) as Refinement.Refined)
                .value
        val callback = QueryCallbackWireFixture().callbackDocument()
        val observed = callback.flow as QueryCallbackFlowDocument.Observed
        val bound = observed.binding as QueryCallbackBindingDocument.Bound
        val direct =
            QueryCallbackBindingDocument.Direct(observed.basis, bound.invocationOccurrence, bound.invocationOwner)
        val uses =
            listOf(
                QueryImmutableCallbackUseDocument.Unused(root),
                QueryImmutableCallbackUseDocument.Direct(root, direct),
            )
        return (QueryImmutableCallbackFlowDocument.create(
                root,
                bounded(uses),
                bounded(emptyList()),
                QueryCallbackInvocationScanDocument.EXHAUSTIVE,
            ) as Refinement.Refined)
            .value
    }

    private fun <T> bounded(values: List<T>) = (BoundedProtocolList.create(values) as Refinement.Refined).value
}
