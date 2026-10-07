package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryCallbackGraphOwnerBindingWireTest {
    private val fixture = QueryCallbackRefinementWireFixture()
    private val source = QueryCallbackWireFixture()

    @Test
    fun `non invoking convergent graph branch retains its anonymous owner activation proof after raw admission`() {
        val wire = withNonInvokingGraphOwner()
        val flow = wire.flow as QueryCallbackFlowWireDocument.Observed
        val owner = flow.ownerBindings.single()
        val admitted = encodedContract(wire)
        assertTrue(admitted is WireDocumentConversion.Converted)
        val converted = admitted as WireDocumentConversion.Converted
        val retained = converted.value.toWireDocument().flow as QueryCallbackFlowWireDocument.Observed
        assertEquals(flow.forwarding, retained.forwarding)
        assertEquals(listOf(owner), retained.ownerBindings)
        assertEquals(QueryCallbackInvocationScanDocument.INCOMPLETE, retained.scan)
        assertTrue(
            flow.invocations.none {
                it.owner == owner.body ||
                    it.forwardings.any { edge ->
                        (edge.target as QueryCallbackBindingWireDocument.Bound).invocationOwner == owner.body
                    }
            }
        )
        val graph = retained.forwarding as QueryCallbackForwardingEvidenceWireDocument.ExhaustedGraph
        assertEquals(2, graph.forwardings.size)
        assertEquals(
            owner.body,
            (graph.forwardings.last().target as QueryCallbackBindingWireDocument.Bound).invocationOwner,
        )
    }

    @Test
    fun `anonymous graph forwarding without an invocation witness still requires the nested execution obligation`() {
        val wire = withNonInvokingGraphOwner()
        val flow = wire.flow as QueryCallbackFlowWireDocument.Observed
        val unqualified =
            flow.copy(ownerBindings = emptyList(), obligations = listOf(QueryCallbackFlowCauseDocument.STORED_CALLBACK))
        assertEquals(WireDocumentConversion.Rejected, encodedContract(wire.copy(flow = unqualified)))
        val qualified =
            unqualified.copy(
                obligations =
                    listOf(
                        QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION,
                        QueryCallbackFlowCauseDocument.STORED_CALLBACK,
                    )
            )
        assertTrue(encodedContract(wire.copy(flow = qualified)) is WireDocumentConversion.Converted)
    }

    private fun withNonInvokingGraphOwner(): QueryCallbackObservationWireDocument {
        val wire = fixture.exhaustedGraphObservation()
        val flow = wire.flow as QueryCallbackFlowWireDocument.Observed
        val graph = flow.forwarding as QueryCallbackForwardingEvidenceWireDocument.ExhaustedGraph
        val original = graph.forwardings.single()
        val target = original.target as QueryCallbackBindingWireDocument.Bound
        val body = fixture.anonymous("Boundary.kt", 10, 25)
        val range = source.occurrence("Boundary.kt", 12, 20).range
        val branch =
            original.copy(
                target =
                    target.copy(
                        invocation =
                            target.invocation.copy(
                                range = ImpactSourceRangeDocument(range.startInclusive, range.endExclusive)
                            ),
                        invocationOccurrence = fixture.occurrence("Boundary.kt", 12, 20),
                        invocationOwner = body,
                    )
            )
        val obligations =
            listOf(
                QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION,
                QueryCallbackFlowCauseDocument.STORED_CALLBACK,
            )
        val owner =
            QueryCallbackBodyBindingWireDocument(
                body,
                QueryCallbackBodySupplyWireDocument.Stored,
                QueryCallbackBindingWireDocument.Unavailable(QueryCallbackFlowCauseDocument.STORED_CALLBACK),
                obligations,
            )
        return wire.copy(
            flow =
                flow.copy(
                    forwarding = graph.copy(forwardings = graph.forwardings + branch),
                    ownerBindings = listOf(owner),
                    obligations = obligations,
                    scan = QueryCallbackInvocationScanDocument.INCOMPLETE,
                )
        )
    }

    private fun encodedContract(wire: QueryCallbackObservationWireDocument) = fixture.decode(wire).toContract()
}
