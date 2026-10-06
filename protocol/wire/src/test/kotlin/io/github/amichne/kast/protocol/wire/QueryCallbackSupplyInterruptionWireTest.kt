package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryCallbackSupplyInterruptionWireTest {
    private val fixture = QueryCallbackRefinementWireFixture()
    private val causes =
        listOf(QueryCallbackFlowCauseDocument.WORK_LIMIT_REACHED, QueryCallbackFlowCauseDocument.TIME_LIMIT_REACHED)

    @Test
    fun `nested known default and direct supplies retain exact work and time exhaustion causes`() {
        for (supply in supplies()) for (cause in causes) {
            val wire = fixture.nestedObservation(supply, cause)
            fixture.assertAdmitted(wire)
            val decoded = fixture.flow(fixture.decode(wire))
            assertEquals(supply, decoded.ownerBindings.single().supply)
            assertEquals(
                cause,
                (decoded.ownerBindings.single().binding as QueryCallbackBindingWireDocument.Unavailable).cause,
            )
            assertEquals(listOf(QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION, cause), decoded.obligations)
        }
    }

    @Test
    fun `nested unavailable mapping rejects erased cause and truncated known supply`() {
        for (supply in supplies()) for (cause in causes) {
            val wire = fixture.nestedObservation(supply, cause)
            val flow = fixture.flow(wire)
            val owner = flow.ownerBindings.single()
            for (wrong in
                listOf(
                    owner.copy(obligations = listOf(QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION)),
                    owner.copy(supply = truncate(supply)),
                )) fixture.assertRejected(wire.copy(flow = flow.copy(ownerBindings = listOf(wrong))))
        }
    }

    private fun supplies(): List<QueryCallbackBodySupplyWireDocument> {
        val boundary = fixture.occurrence("Boundary.kt", 9, 26)
        return listOf(
            QueryCallbackBodySupplyWireDocument.DefaultParameter(boundary),
            QueryCallbackBodySupplyWireDocument.DirectInvocation(boundary),
        )
    }

    private fun truncate(supply: QueryCallbackBodySupplyWireDocument): QueryCallbackBodySupplyWireDocument =
        when (supply) {
            is QueryCallbackBodySupplyWireDocument.DefaultParameter ->
                supply.copy(parameter = fixture.occurrence("Boundary.kt", 9, 13))
            is QueryCallbackBodySupplyWireDocument.DirectInvocation ->
                supply.copy(occurrence = fixture.occurrence("Boundary.kt", 9, 13))
            is QueryCallbackBodySupplyWireDocument.Invocation,
            is QueryCallbackBodySupplyWireDocument.Returned,
            QueryCallbackBodySupplyWireDocument.Stored,
            QueryCallbackBodySupplyWireDocument.Unsupported -> error("Unexpected test supply")
        }
}
