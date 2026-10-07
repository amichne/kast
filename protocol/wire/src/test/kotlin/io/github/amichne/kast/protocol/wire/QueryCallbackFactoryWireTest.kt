package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryCallbackFactoryBodyCallsDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFactoryReturnDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument
import io.github.amichne.kast.protocol.contract.QueryImmutableCallbackValueDocument
import io.github.amichne.kast.protocol.contract.QueryImmutableCallbackValueOriginDocument
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryCallbackFactoryWireTest {
    @Test
    fun `returned callable encodes original producer and separate exact factory activation`() {
        val value = fixture()
        val wire = value.immutableCallbackWire()
        val encoded =
            wireJson.encodeToJsonElement(QueryImmutableCallbackValueWireDocument.serializer(), wire).jsonObject
        assertEquals(JsonPrimitive("RETURNED"), encoded.getValue("origin").jsonObject.getValue("type"))
        assertEquals(JsonPrimitive(0), encoded.getValue("origin").jsonObject.getValue("factory_index"))
        val factory = encoded.getValue("factories").jsonArray.single().jsonObject
        assertEquals(
            setOf("enclosing", "invocation", "callable", "returned_value", "captures", "body_calls"),
            factory.keys,
        )
        assertEquals(JsonPrimitive("EXHAUSTIVE"), factory.getValue("body_calls").jsonObject.getValue("type"))
        assertEquals(
            emptyList<kotlinx.serialization.json.JsonElement>(),
            factory.getValue("body_calls").jsonObject.getValue("calls").jsonArray.toList(),
        )
        assertEquals(
            JsonPrimitive("ANONYMOUS"),
            factory.getValue("returned_value").jsonObject.getValue("origin").jsonObject.getValue("type"),
        )
        assertEquals(WireDocumentConversion.Converted(value), wire.toContract())
        assertEquals(WireDocumentConversion.Rejected, wire.copy(factories = emptyList()).toContract())
        assertEquals(
            WireDocumentConversion.Rejected,
            wire.copy(origin = QueryImmutableCallbackValueOriginWireDocument.Returned(-1)).toContract(),
        )
        assertEquals(
            WireDocumentConversion.Rejected,
            wire.copy(origin = QueryImmutableCallbackValueOriginWireDocument.Returned(1)).toContract(),
        )
    }

    @Test
    fun `cycles forward references and unrelated factory entries reject`() {
        val wire = fixture().immutableCallbackWire()
        val factory = wire.factories.single()
        val selfReference =
            factory.copy(
                returnedValue =
                    factory.returnedValue.copy(origin = QueryImmutableCallbackValueOriginWireDocument.Returned(0))
            )
        assertEquals(WireDocumentConversion.Rejected, wire.copy(factories = listOf(selfReference)).toContract())
        assertEquals(WireDocumentConversion.Rejected, wire.copy(factories = listOf(factory, factory)).toContract())
        val wrongProducer =
            factory.copy(
                returnedValue = factory.returnedValue.copy(source = wire.source, destination = wire.destination)
            )
        assertEquals(WireDocumentConversion.Rejected, wire.copy(factories = listOf(wrongProducer)).toContract())
    }

    internal fun fixture(): QueryImmutableCallbackValueDocument {
        val fixture = QueryCallbackWireFixture()
        val callback = fixture.callbackDocument()
        val flow = callback.flow as QueryCallbackFlowDocument.Observed
        val producer = callback.lexicalOwner
        val consumer = fixture.callable("Consumer.kt", 0, 80, "sample.consume", emptyList())
        fun reference(callable: io.github.amichne.kast.protocol.contract.QueryCallbackCallableDocument) =
            ImpactDeclarationReferenceDocument(
                flow.basis,
                callable.declaration.file,
                ImpactSourceRangeDocument(
                    callable.declaration.range.startInclusive,
                    callable.declaration.range.endExclusive,
                ),
                callable.compilerTarget.compilerEvidence.identity,
            )
        val original =
            ImpactValueSiteReferenceDocument(
                reference(producer),
                range(4, 18),
                ImpactValueRoleDocument.ExpressionResult,
            )
        val returned =
            QueryImmutableCallbackValueDocument.create(
                    QueryImmutableCallbackValueOriginDocument.Anonymous(flow.body),
                    original,
                    original,
                    bounded(emptyList()),
                    bounded(emptyList()),
                )
                .value()
        val call = ImpactInvocationReferenceDocument(range(20, 30), reference(producer))
        val factory =
            QueryCallbackFactoryReturnDocument.create(
                    reference(consumer),
                    call,
                    producer,
                    returned,
                    bounded(emptyList()),
                    QueryCallbackFactoryBodyCallsDocument.Exhaustive.create(flow.body, bounded(emptyList())).value(),
                )
                .value()
        val result =
            ImpactValueSiteReferenceDocument(reference(consumer), call.range, ImpactValueRoleDocument.ExpressionResult)
        return QueryImmutableCallbackValueDocument.create(
                QueryImmutableCallbackValueOriginDocument.Returned(factory),
                result,
                result,
                bounded(emptyList()),
                bounded(emptyList()),
            )
            .value()
    }

    private fun range(start: Int, end: Int) =
        ImpactSourceRangeDocument(ProtocolOffset.parse(start).value(), ProtocolOffset.parse(end).value())

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

    private fun <V, F> Refinement<V, F>.value(): V = (this as Refinement.Refined).value
}
