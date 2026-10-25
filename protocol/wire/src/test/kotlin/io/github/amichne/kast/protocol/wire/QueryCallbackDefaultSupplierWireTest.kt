package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBodyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackParameterIdentityDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackParameterSupplierDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackSupplierSelectionDocument
import io.github.amichne.kast.protocol.contract.QueryImmutableCallbackValueDocument
import io.github.amichne.kast.protocol.contract.QueryImmutableCallbackValueOriginDocument
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryCallbackDefaultSupplierWireTest {
    @Test
    fun `nested default supplier rejects another compiler root at the same declaration site`() {
        val fixture = QueryCallbackWireFixture()
        val inventory = inventory(fixture)
        assertTrue(inventory.toContract() is WireDocumentConversion.Converted)
        val encoded = wireJson.encodeToString(QueryCallbackSupplierInventoryWireDocument.serializer(), inventory)
        val decoded = wireJson.decodeFromString(QueryCallbackSupplierInventoryWireDocument.serializer(), encoded)
        assertEquals(inventory, decoded)
        assertTrue(decoded.toContract() is WireDocumentConversion.Converted)
        val partition = inventory.partitions.single()
        val owner = partition.ownerBindings.single()
        val binding = owner.binding as QueryCallbackBindingWireDocument.Direct
        val execution = (owner.execution as QueryCallbackOwnerExecutionWireDocument.Observed).flow
        val foreign =
            binding.copy(
                owner =
                    QueryCallbackBodyWireDocument.Named(
                        fixture.callable("Seed.kt", 0, 40, "sample.other", emptyList()).callbackWire()
                    )
            )
        val replaced =
            owner.copy(
                binding = foreign,
                execution = QueryCallbackOwnerExecutionWireDocument.Observed(execution.copy(binding = foreign)),
            )
        assertEquals(
            WireDocumentConversion.Rejected,
            inventory.copy(partitions = listOf(partition.copy(ownerBindings = listOf(replaced)))).toContract(),
        )
    }

    @Test
    @OptIn(ExperimentalSerializationApi::class)
    fun `default selection encodes the required invocation enclosing identity`() {
        val inventory = inventory(QueryCallbackWireFixture())
        val selection =
            inventory.partitions.single().suppliers.single().selection
                as QueryCallbackSupplierSelectionWireDocument.Default
        val encoded =
            wireJson.encodeToJsonElement(QueryCallbackSupplierSelectionWireDocument.serializer(), selection).jsonObject
        assertEquals(setOf("type", "declaration", "invocation_enclosing"), encoded.keys)
        assertEquals(JsonPrimitive("DEFAULT"), encoded.getValue("type"))
        assertEquals(
            wireJson.encodeToJsonElement(
                io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument.serializer(),
                selection.invocationEnclosing,
            ),
            encoded.getValue("invocation_enclosing"),
        )
        val failure =
            assertThrows(MissingFieldException::class.java) {
                wireJson.decodeFromString(
                    QueryCallbackSupplierSelectionWireDocument.serializer(),
                    wireJson.encodeToString(
                        DefaultWithoutEnclosing.serializer(),
                        DefaultWithoutEnclosing("DEFAULT", selection.declaration),
                    ),
                )
            }
        assertEquals(listOf("invocation_enclosing"), failure.missingFields)
    }

    private fun inventory(fixture: QueryCallbackWireFixture): QueryCallbackSupplierInventoryWireDocument.Exhaustive {
        val callback = fixture.callbackDocument()
        val flow = callback.flow as QueryCallbackFlowDocument.Observed
        val original = flow.binding as QueryCallbackBindingDocument.Bound
        val formal = QueryCallbackParameterIdentityDocument(original.callable, original.position, original.parameter)
        val body =
            QueryCallbackBodyDocument.Anonymous(
                fixture.occurrence("Boundary.kt", 3, 8),
                fixture.signature("anonymous@Boundary.kt#3:8", emptyList()),
            )
        val defaultValue =
            ImpactValueSiteReferenceDocument(
                original.invocation.callable,
                ImpactSourceRangeDocument(body.occurrence.range.startInclusive, body.occurrence.range.endExclusive),
                ImpactValueRoleDocument.ExpressionResult,
            )
        val value =
            QueryImmutableCallbackValueDocument.create(
                    QueryImmutableCallbackValueOriginDocument.Anonymous(body),
                    defaultValue,
                    defaultValue,
                    bounded(emptyList()),
                    bounded(emptyList()),
                )
                .value()
        val base = QueryCallbackSupplierSubjectWireTest().nestedInventory()
        val partition = base.partitions.single()
        val binding =
            (partition.suppliers.single().binding.toContract() as WireDocumentConversion.Converted).value
                as QueryCallbackBindingDocument.Bound
        val supplier =
            QueryCallbackParameterSupplierDocument.create(
                    binding,
                    QueryCallbackSupplierSelectionDocument.Default(
                        QueryCallbackBindingDocument.Default(formal, body.occurrence),
                        (partition.suppliers.single().selection as QueryCallbackSupplierSelectionWireDocument.Explicit)
                            .argument
                            .enclosing,
                    ),
                    value,
                )
                .value()
        return base.copy(partitions = listOf(partition.copy(suppliers = listOf(supplier.callbackSupplierWire()))))
    }

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

    private fun <T, F> Refinement<T, F>.value(): T = (this as Refinement.Refined).value
}

@Serializable
private data class DefaultWithoutEnclosing(val type: String, val declaration: QueryCallbackBindingWireDocument)
