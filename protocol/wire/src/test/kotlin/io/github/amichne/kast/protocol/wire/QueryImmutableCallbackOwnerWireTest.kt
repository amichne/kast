package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackParameterSupplierDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackSupplierSelectionDocument
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryImmutableCallbackOwnerWireTest {
    @Test
    fun `stored callback use preserves its complete anonymous activation owner`() {
        val wire = QueryCallbackOwnerExecutionWireFixture().nestedImmutable()
        val admitted = wire.toContract()
        assertTrue(admitted is WireDocumentConversion.Converted)
        admitted as WireDocumentConversion.Converted
        assertEquals(wire, admitted.value.toWireDocument())
        val observed = wire.flow as QueryCallbackFlowWireDocument.Observed
        val owner = observed.ownerBindings.single()
        val execution = owner.execution as QueryCallbackOwnerExecutionWireDocument.Immutable
        assertEquals(emptyList<QueryCallbackFlowCauseDocument>(), execution.flow.obligations)
        assertTrue(execution.flow.ownerBindings.isNotEmpty())
        val missingOwner = execution.copy(flow = execution.flow.copy(ownerBindings = emptyList()))
        assertEquals(
            WireDocumentConversion.Rejected,
            wire.copy(flow = observed.copy(ownerBindings = listOf(owner.copy(execution = missingOwner)))).toContract(),
        )
    }

    @Test
    fun `supplied callback keeps formal body owner proofs separate from source use owners`() {
        val flow = supplied()
        val encoded = wireJson.encodeToJsonElement(QueryImmutableCallbackFlowWireDocument.serializer(), flow)
        val supplied = encoded.jsonObject.getValue("uses").jsonArray.single().jsonObject
        assertEquals(
            setOf("type", "supplier", "invocations", "forwarding", "summary_owner_bindings", "summary_obligations"),
            supplied.keys,
        )
        assertEquals(
            listOf("NESTED_CALLBACK_EXECUTION"),
            supplied.getValue("summary_obligations").jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(encoded.jsonObject.getValue("obligations").jsonArray.isEmpty())
        val admitted =
            wireJson
                .decodeFromString(QueryImmutableCallbackFlowWireDocument.serializer(), encoded.toString())
                .toContract()
        assertTrue(admitted is WireDocumentConversion.Converted)
        admitted as WireDocumentConversion.Converted
        assertEquals(flow, admitted.value.immutableFlowWire())
        val use = flow.uses.single() as QueryImmutableCallbackUseWireDocument.Supplied
        assertEquals(
            WireDocumentConversion.Rejected,
            flow.copy(uses = listOf(use.copy(summaryOwnerBindings = emptyList()))).toContract(),
        )
        assertEquals(WireDocumentConversion.Rejected, flow.copy(ownerBindings = use.summaryOwnerBindings).toContract())
        val nested = QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION
        val unproven =
            use.summaryOwnerBindings
                .single()
                .copy(
                    execution = QueryCallbackOwnerExecutionWireDocument.Unproven,
                    obligations = listOf(nested),
                )
        val incomplete = use.copy(summaryOwnerBindings = listOf(unproven), summaryObligations = listOf(nested))
        assertTrue(
            flow.copy(uses = listOf(incomplete), obligations = listOf(nested)).toContract()
                is WireDocumentConversion.Converted
        )
        assertEquals(WireDocumentConversion.Rejected, flow.copy(uses = listOf(incomplete)).toContract())
    }

    private fun supplied(): QueryImmutableCallbackFlowWireDocument {
        val value = QueryImmutableCallbackValueWireTest().fixture()
        val role = value.destination.role as ImpactValueRoleDocument.Argument
        val fixture = QueryCallbackWireFixture()
        val callback = fixture.callbackDocument()
        val observed = callback.flow as QueryCallbackFlowDocument.Observed
        val original = observed.binding as QueryCallbackBindingDocument.Bound
        val binding =
            original.copy(
                invocation = role.invocation,
                invocationOccurrence = fixture.occurrence("Seed.kt", 28, 38),
            )
        val supplier =
            (QueryCallbackParameterSupplierDocument.create(
                    binding,
                    QueryCallbackSupplierSelectionDocument.Explicit(value.destination),
                    value,
                ) as Refinement.Refined)
                .value
        val composed =
            QueryCallbackOwnerExecutionWireFixture().composed().flow as QueryCallbackFlowWireDocument.Observed
        val source =
            value
                .immutableCallbackWire()
                .copy(destination = value.source, transfers = emptyList(), invokedCallables = emptyList())
        return QueryImmutableCallbackFlowWireDocument(
            source,
            listOf(
                QueryImmutableCallbackUseWireDocument.Supplied(
                    supplier.callbackSupplierWire(),
                    composed.invocations,
                    composed.forwarding,
                    composed.ownerBindings,
                    listOf(QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION),
                )
            ),
            emptyList(),
            QueryCallbackInvocationScanDocument.EXHAUSTIVE,
        )
    }

    @Test
    fun `stored callback use preserves an opaque activation cause and cannot erase it`() {
        val wire = QueryCallbackOwnerExecutionWireFixture().nestedImmutable()
        val observed = wire.flow as QueryCallbackFlowWireDocument.Observed
        val execution = observed.ownerBindings.single().execution as QueryCallbackOwnerExecutionWireDocument.Immutable
        val causes =
            listOf(
                    QueryCallbackFlowCauseDocument.EXTERNAL_CALLABLE,
                    QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION,
                )
                .sortedBy { it.ordinal }
        val outer =
            execution.flow.ownerBindings
                .single()
                .copy(
                    binding =
                        QueryCallbackBindingWireDocument.Unavailable(QueryCallbackFlowCauseDocument.EXTERNAL_CALLABLE),
                    obligations = causes,
                    execution = QueryCallbackOwnerExecutionWireDocument.Unproven,
                )
        val gap =
            execution.flow.copy(
                obligations = causes,
                scan = QueryCallbackInvocationScanDocument.INCOMPLETE,
                ownerBindings = listOf(outer),
            )
        val admitted = gap.toContract()
        assertTrue(admitted is WireDocumentConversion.Converted)
        admitted as WireDocumentConversion.Converted
        assertEquals(gap, admitted.value.immutableFlowWire())
        assertEquals(WireDocumentConversion.Rejected, gap.copy(obligations = emptyList()).toContract())
        assertEquals(
            WireDocumentConversion.Rejected,
            gap.copy(scan = QueryCallbackInvocationScanDocument.EXHAUSTIVE).toContract(),
        )
        assertEquals(
            WireDocumentConversion.Rejected,
            gap.copy(obligations = listOf(QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION)).toContract(),
        )
    }
}
