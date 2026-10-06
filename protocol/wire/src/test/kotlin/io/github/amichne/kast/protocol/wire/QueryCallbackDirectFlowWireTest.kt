package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryCallbackDirectFlowWireTest {
    private val fixture = QueryCallbackRefinementWireFixture()

    @Test
    fun `direct body preserves exact invocation and separate admitted policy`() {
        val wire = fixture.directObservation()
        fixture.assertAdmitted(wire)
        val raw = wireJson.encodeToJsonElement(QueryCallbackObservationWireDocument.serializer(), wire).jsonObject
        val encoded = raw.getValue("flow").jsonObject.getValue("binding").jsonObject
        assertEquals(setOf("type", "basis", "occurrence", "owner"), encoded.keys)
        assertEquals("DIRECT", encoded.getValue("type").jsonPrimitive.content)
        assertEquals("ADMITTED_DIRECT", raw.getValue("named_policy").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `direct flow cannot fabricate a formal scan or truncate its body`() {
        val wire = fixture.directObservation()
        val flow = fixture.flow(wire)
        val direct = flow.binding as QueryCallbackBindingWireDocument.Direct
        for (wrong in
            listOf(
                flow.copy(scan = QueryCallbackInvocationScanDocument.EXHAUSTIVE),
                flow.copy(invocations = fixture.flow(fixture.base()).invocations),
                flow.copy(binding = direct.copy(occurrence = fixture.occurrence("Seed.kt", 8, 20))),
            )) fixture.assertRejected(wire.copy(flow = wrong))
    }

    @Test
    fun `direct owner binding preserves matched supply and rejects truncated source`() {
        val wire = fixture.directObservation()
        val flow = fixture.flow(wire)
        val direct = flow.binding as QueryCallbackBindingWireDocument.Direct
        val owner =
            QueryCallbackBodyBindingWireDocument(
                flow.body,
                QueryCallbackBodySupplyWireDocument.DirectInvocation(direct.occurrence),
                direct,
                listOf(QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION),
            )
        fixture.assertAdmitted(
            wire.copy(flow = flow.copy(ownerBindings = listOf(owner), obligations = owner.obligations))
        )
        fixture.assertRejected(
            wire.copy(
                flow =
                    flow.copy(
                        ownerBindings =
                            listOf(
                                owner.copy(
                                    supply =
                                        QueryCallbackBodySupplyWireDocument.DirectInvocation(
                                            fixture.occurrence("Seed.kt", 8, 20)
                                        )
                                )
                            ),
                        obligations = owner.obligations,
                    )
            )
        )
    }

    @Test
    fun `direct flow in anonymous owner retains its execution obligation`() {
        val wire = fixture.directObservation()
        val flow = fixture.flow(wire)
        val direct = flow.binding as QueryCallbackBindingWireDocument.Direct
        val nested = flow.copy(binding = direct.copy(owner = fixture.anonymous("Seed.kt", 1, 25)))
        fixture.assertRejected(wire.copy(flow = nested))
        fixture.assertAdmitted(
            wire.copy(
                flow = nested.copy(obligations = listOf(QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION))
            )
        )
    }
}
