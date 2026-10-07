package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryCallableObservationDocument
import io.github.amichne.kast.protocol.contract.QueryCallableTargetDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackForwardingEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackParameterIdentityDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackParameterSupplierDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackSupplierSelectionDocument
import io.github.amichne.kast.protocol.contract.QueryImmutableCallbackUseDocument
import io.github.amichne.kast.protocol.contract.QueryImmutableCallbackValueDocument
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QuerySelectedCallbackWireTest {
    @Test
    fun `selected supplies retain exact required formal inventory and reject partial duplicate or foreign proofs`() {
        val wire = suppliedObservation().toWireDocument()
        val encoded = wireJson.encodeToJsonElement(QueryCallableObservationWireDocument.serializer(), wire).jsonObject
        val target = encoded.getValue("target").jsonObject
        assertEquals(setOf("type", "supplies", "formals"), target.keys)
        assertEquals(JsonPrimitive("CALLBACK_SUPPLIES"), target.getValue("type"))
        val selected = wire.target as QueryCallableTargetWireDocument.CallbackSupplies
        assertEquals(1, selected.formals.size)
        assertEquals(0, selected.supplies.single().invocations.size)
        assertEquals(WireDocumentConversion.Converted(suppliedObservation()), wire.toContract())
        for (invalid in
            listOf(
                selected.copy(supplies = emptyList()),
                selected.copy(supplies = selected.supplies + selected.supplies),
                selected.copy(formals = emptyList()),
                selected.copy(formals = selected.formals + selected.formals),
                selected.copy(formals = listOf(selected.formals.single().copy(position = 1))),
            )) assertEquals(WireDocumentConversion.Rejected, wire.copy(target = invalid).toContract())
    }

    @Test
    fun `direct selected invocation keeps exact value and rejects empty duplicate or foreign occurrence`() {
        val value = directObservation()
        val wire = value.toWireDocument()
        val target =
            wireJson
                .encodeToJsonElement(QueryCallableObservationWireDocument.serializer(), wire)
                .jsonObject
                .getValue("target")
                .jsonObject
        assertEquals(setOf("type", "invocations"), target.keys)
        assertEquals(JsonPrimitive("DIRECT_INVOCATIONS"), target.getValue("type"))
        assertEquals(WireDocumentConversion.Converted(value), wire.toContract())
        val selected = wire.target as QueryCallableTargetWireDocument.DirectInvocations
        assertEquals(
            WireDocumentConversion.Rejected,
            wire.copy(target = selected.copy(invocations = emptyList())).toContract(),
        )
        assertEquals(
            WireDocumentConversion.Rejected,
            wire.copy(target = selected.copy(invocations = selected.invocations + selected.invocations)).toContract(),
        )
        assertEquals(
            WireDocumentConversion.Rejected,
            wire.copy(occurrence = wire.occurrence.copy(file = "Foreign.kt")).toContract(),
        )
    }

    @Test
    fun `unavailable selected supplies retain finite causes and reject missing obligations`() {
        val valid = suppliedObservation().toWireDocument()
        val target =
            QueryCallableTargetWireDocument.UnavailableSupply(
                listOf(io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument.STORED_CALLBACK)
            )
        val wire = valid.copy(target = target)
        val encoded =
            wireJson
                .encodeToJsonElement(QueryCallableObservationWireDocument.serializer(), wire)
                .jsonObject
                .getValue("target")
                .jsonObject
        assertEquals(setOf("type", "causes"), encoded.keys)
        assertEquals(JsonPrimitive("UNAVAILABLE_SUPPLY"), encoded.getValue("type"))
        org.junit.jupiter.api.Assertions.assertTrue(wire.toContract() is WireDocumentConversion.Converted)
        assertEquals(
            WireDocumentConversion.Rejected,
            wire.copy(target = target.copy(causes = emptyList())).toContract(),
        )
    }

    private fun suppliedObservation(): QueryCallableObservationDocument {
        val callback = QueryCallbackWireFixture().callbackDocument()
        val flow = callback.flow as QueryCallbackFlowDocument.Observed
        val original = flow.binding as QueryCallbackBindingDocument.Bound
        val value = QueryImmutableCallbackValueWireTest().fixture()
        val role = value.destination.role as ImpactValueRoleDocument.Argument
        val occurrence = original.invocationOccurrence.copy(range = range(28, 38))
        val binding = original.copy(invocation = role.invocation, invocationOccurrence = occurrence)
        val supplier =
            (QueryCallbackParameterSupplierDocument.create(
                    binding,
                    QueryCallbackSupplierSelectionDocument.Explicit(value.destination),
                    value,
                ) as Refinement.Refined)
                .value
        val supplied =
            QueryImmutableCallbackUseDocument.Supplied(
                supplier,
                bounded(emptyList()),
                QueryCallbackForwardingEvidenceDocument.InvocationRoutes,
            )
        val formal = QueryCallbackParameterIdentityDocument(binding.callable, binding.position, binding.parameter)
        return (QueryCallableObservationDocument.create(
                occurrence,
                callback.lexicalOwner,
                binding.invocationOwner,
                QueryCallableTargetDocument.CallbackSupplies(bounded(listOf(supplied)), bounded(listOf(formal))),
            ) as Refinement.Refined)
            .value
    }

    private fun directObservation(): QueryCallableObservationDocument {
        val callback = QueryCallbackWireFixture().callbackDocument()
        val flow = callback.flow as QueryCallbackFlowDocument.Observed
        val bound = flow.binding as QueryCallbackBindingDocument.Bound
        val value = QueryImmutableCallbackValueWireTest().fixture()
        val source =
            (QueryImmutableCallbackValueDocument.create(
                    value.origin,
                    value.source,
                    value.source,
                    bounded(emptyList()),
                    bounded(emptyList()),
                ) as Refinement.Refined)
                .value
        val binding = QueryCallbackBindingDocument.Direct(flow.basis, bound.invocationOccurrence, bound.invocationOwner)
        return (QueryCallableObservationDocument.create(
                binding.occurrence,
                callback.lexicalOwner,
                binding.owner,
                QueryCallableTargetDocument.DirectInvocations(
                    bounded(listOf(QueryImmutableCallbackUseDocument.Direct(source, binding)))
                ),
            ) as Refinement.Refined)
            .value
    }

    private fun range(start: Int, end: Int) =
        (SourceRangeDocument.create(offset(start), offset(end)) as Refinement.Refined).value

    private fun offset(value: Int) = (ProtocolOffset.parse(value) as Refinement.Refined).value

    private fun <T> bounded(values: List<T>) = (BoundedProtocolList.create(values) as Refinement.Refined).value
}
