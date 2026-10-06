package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryCallbackDefaultFlowWireTest {
    private val fixture = QueryCallbackRefinementWireFixture()

    @Test
    fun `default body retains formal identity default source and proven empty scan`() {
        val wire = fixture.defaultObservation()
        fixture.assertAdmitted(wire)
        val encoded = wireJson.encodeToJsonElement(QueryCallbackObservationWireDocument.serializer(), wire).jsonObject
        val binding = encoded.getValue("flow").jsonObject.getValue("binding").jsonObject
        assertEquals(setOf("type", "parameter", "default_value"), binding.keys)
        assertEquals("DEFAULT", binding.getValue("type").jsonPrimitive.content)
        assertEquals(setOf("callable", "position", "parameter"), binding.getValue("parameter").jsonObject.keys)
    }

    @Test
    fun `default mapping rejects invalid formal source and scan proofs`() {
        val wire = fixture.defaultObservation()
        val flow = fixture.flow(wire)
        val default = flow.binding as QueryCallbackBindingWireDocument.Default
        val parameter = default.parameter
        for (wrong in
            listOf(
                flow.copy(scan = QueryCallbackInvocationScanDocument.INCOMPLETE),
                flow.copy(obligations = listOf(QueryCallbackFlowCauseDocument.CALLBACK_CYCLE)),
                flow.copy(obligations = listOf(QueryCallbackFlowCauseDocument.WORK_LIMIT_REACHED)),
                flow.copy(scan = QueryCallbackInvocationScanDocument.NOT_APPLICABLE),
                flow.copy(binding = default.copy(parameter = parameter.copy(position = 1))),
                flow.copy(binding = default.copy(defaultValue = fixture.occurrence("Seed.kt", 18, 21))),
                flow.copy(
                    binding = default.copy(parameter = parameter.copy(parameter = fixture.occurrence("Seed.kt", 2, 3)))
                ),
            )) fixture.assertRejected(wire.copy(flow = wrong))
    }

    @Test
    fun `default owner binding preserves exact mapping and requires its unavailable cause`() {
        val wire = fixture.defaultObservation()
        val flow = fixture.flow(wire)
        val default = flow.binding as QueryCallbackBindingWireDocument.Default
        val owner =
            QueryCallbackBodyBindingWireDocument(
                flow.body,
                QueryCallbackBodySupplyWireDocument.DefaultParameter(default.parameter.parameter),
                default,
                listOf(QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION),
            )
        fixture.assertAdmitted(
            wire.copy(flow = flow.copy(ownerBindings = listOf(owner), obligations = owner.obligations))
        )
        val missingOwnerCause =
            owner.copy(
                binding =
                    QueryCallbackBindingWireDocument.Unavailable(
                        QueryCallbackFlowCauseDocument.UNRESOLVED_ARGUMENT_MAPPING
                    )
            )
        fixture.assertRejected(
            wire.copy(
                flow =
                    flow.copy(
                        ownerBindings = listOf(missingOwnerCause),
                        obligations = owner.obligations + QueryCallbackFlowCauseDocument.UNRESOLVED_ARGUMENT_MAPPING,
                        scan = QueryCallbackInvocationScanDocument.INCOMPLETE,
                    )
            )
        )
    }
}
