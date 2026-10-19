package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QuerySourceLessCallableDispositionDocument
import io.github.amichne.kast.protocol.contract.QuerySourceLessCallableModuleKindDocument
import io.github.amichne.kast.protocol.contract.QuerySourceLessCallableOriginDocument
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryCallbackFactoryBodyCallsWireTest {
    private val fixture = QueryCallbackWireFixture()

    @Test
    fun `named body call retains exact execution owner occurrence and target`() {
        val wire = QueryCallbackFactoryWireTest().fixture().immutableCallbackWire()
        val factory = wire.factories.single()
        val body = factory.bodyCalls as QueryCallbackFactoryBodyCallsWireDocument.Exhaustive
        val named = named()
        val inventory = body.copy(calls = listOf(named))
        val updated = wire.copy(factories = listOf(factory.copy(bodyCalls = inventory)))
        val encoded =
            wireJson.encodeToJsonElement(QueryCallbackFactoryBodyCallsWireDocument.serializer(), inventory).jsonObject
        assertEquals(setOf("type", "body", "calls"), encoded.keys)
        assertEquals(JsonPrimitive("EXHAUSTIVE"), encoded.getValue("type"))
        val call = encoded.getValue("calls").jsonArray.single().jsonObject
        assertEquals(setOf("type", "occurrence", "target"), call.keys)
        assertEquals(JsonPrimitive("NAMED"), call.getValue("type"))
        val decoded = assertInstanceOf(WireDocumentConversion.Converted::class.java, updated.toContract())
        assertEquals(
            updated,
            (decoded.value as io.github.amichne.kast.protocol.contract.QueryImmutableCallbackValueDocument)
                .immutableCallbackWire(),
        )
        assertEquals(
            WireDocumentConversion.Rejected,
            wire
                .copy(factories = listOf(factory.copy(bodyCalls = body.copy(calls = listOf(named, named)))))
                .toContract(),
        )
        val outside = named.copy(occurrence = fixture.occurrence("Seed.kt", 19, 22).callbackWire())
        assertEquals(
            WireDocumentConversion.Rejected,
            wire.copy(factories = listOf(factory.copy(bodyCalls = body.copy(calls = listOf(outside))))).toContract(),
        )
    }

    @Test
    fun `anonymous factory rejects absent inventory and invented captured invocation`() {
        val wire = QueryCallbackFactoryWireTest().fixture().immutableCallbackWire()
        val factory = wire.factories.single()
        val body = factory.bodyCalls as QueryCallbackFactoryBodyCallsWireDocument.Exhaustive
        assertEquals(
            WireDocumentConversion.Rejected,
            wire
                .copy(
                    factories =
                        listOf(factory.copy(bodyCalls = QueryCallbackFactoryBodyCallsWireDocument.NotApplicable))
                )
                .toContract(),
        )
        val formal = fixture.callable("Formal.kt", 0, 40, "sample.formal", listOf("kotlin.Function0<kotlin.Unit>"))
        val captured =
            QueryCallbackFactoryBodyCallWireDocument.Captured(
                QueryCallbackInvocationWireDocument(
                    fixture.occurrence("Seed.kt", 6, 9).callbackWire(),
                    body.body,
                    emptyList(),
                    emptyList(),
                ),
                QueryCallbackParameterIdentityWireDocument(
                    formal.callbackWire(),
                    0,
                    fixture.occurrence("Formal.kt", 2, 8).callbackWire(),
                ),
            )
        val inventory = body.copy(calls = listOf(captured))
        val encoded =
            wireJson
                .encodeToJsonElement(QueryCallbackFactoryBodyCallsWireDocument.serializer(), inventory)
                .jsonObject
                .getValue("calls")
                .jsonArray
                .single()
                .jsonObject
        assertEquals(setOf("type", "invocation", "formal"), encoded.keys)
        assertEquals(JsonPrimitive("CAPTURED"), encoded.getValue("type"))
        assertEquals(
            WireDocumentConversion.Rejected,
            wire.copy(factories = listOf(factory.copy(bodyCalls = inventory))).toContract(),
        )
    }

    @Test
    fun `body boundary preserves module policy and refuses missing library source`() {
        val boundary =
            QueryCallbackFactoryBodyCallWireDocument.Boundary(
                fixture.occurrence("Seed.kt", 6, 9).callbackWire(),
                QuerySourceLessCallableWireDocument(
                    fixture.signature("external.call", emptyList()).toWireDocument(),
                    SymbolKindWireDocument.FUNCTION,
                    QuerySourceLessCallableOriginDocument.LIBRARY,
                    QuerySourceLessCallableModuleKindDocument.LIBRARY,
                    "external-library",
                ),
                QuerySourceLessCallableDispositionDocument.LIBRARY_POLICY_EXCLUDED,
            )
        val wire = QueryCallbackFactoryWireTest().fixture().immutableCallbackWire()
        val factory = wire.factories.single()
        val body = factory.bodyCalls as QueryCallbackFactoryBodyCallsWireDocument.Exhaustive
        val inventory = body.copy(calls = listOf(boundary))
        val encoded =
            wireJson
                .encodeToJsonElement(QueryCallbackFactoryBodyCallsWireDocument.serializer(), inventory)
                .jsonObject
                .getValue("calls")
                .jsonArray
                .single()
                .jsonObject
        assertEquals(setOf("type", "occurrence", "callable", "disposition"), encoded.keys)
        assertEquals(JsonPrimitive("BOUNDARY"), encoded.getValue("type"))
        assertEquals(JsonPrimitive("LIBRARY_POLICY_EXCLUDED"), encoded.getValue("disposition"))
        assertInstanceOf(
            WireDocumentConversion.Converted::class.java,
            wire.copy(factories = listOf(factory.copy(bodyCalls = inventory))).toContract(),
        )
        val unavailable =
            boundary.copy(disposition = QuerySourceLessCallableDispositionDocument.LIBRARY_SOURCE_UNAVAILABLE)
        assertEquals(
            WireDocumentConversion.Rejected,
            wire
                .copy(factories = listOf(factory.copy(bodyCalls = body.copy(calls = listOf(unavailable)))))
                .toContract(),
        )
    }

    @Test
    fun `closed inventory requires discriminator and every factory requires body calls`() {
        val wire = QueryCallbackFactoryWireTest().fixture().immutableCallbackWire()
        val factory = wire.factories.single()
        val inventory =
            wireJson.encodeToString(QueryCallbackFactoryBodyCallsWireDocument.serializer(), factory.bodyCalls)
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(
                QueryCallbackFactoryBodyCallsWireDocument.serializer(),
                inventory.replace("EXHAUSTIVE", "UNKNOWN"),
            )
        }
        val encoded = wireJson.encodeToString(QueryCallbackFactoryWireDocument.serializer(), factory)
        val missing = encoded.replace(",\"body_calls\":$inventory", "")
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(QueryCallbackFactoryWireDocument.serializer(), missing)
        }
        assertEquals(
            JsonPrimitive("NOT_APPLICABLE"),
            wireJson
                .encodeToJsonElement(
                    QueryCallbackFactoryBodyCallsWireDocument.serializer(),
                    QueryCallbackFactoryBodyCallsWireDocument.NotApplicable,
                )
                .jsonObject
                .getValue("type"),
        )
    }

    @Test
    fun `included libraries reject an excluded boundary in returned and owner callbacks`() {
        val callback = fixture.callbackDocument()
        val flow = excludedFactoryFlow()
        for (execution in
            listOf(
                io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument.Immutable(flow),
                ownerFlow(callback, flow),
            )) {
            assertInstanceOf(
                Refinement.Refined::class.java,
                observed(
                    callback,
                    io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
                    execution,
                ),
            )
            assertEquals(
                Refinement.Rejected(
                    io.github.amichne.kast.protocol.contract.QueryCallbackDocumentFailure.INVALID_SCAN_PROOF
                ),
                observed(
                    callback,
                    io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument.INCLUDE,
                    execution,
                ),
            )
        }
    }

    private fun excludedFactoryFlow(): io.github.amichne.kast.protocol.contract.QueryImmutableCallbackFlowDocument {
        val root = QueryCallbackFactoryWireTest().fixture().immutableCallbackWire()
        val factory = root.factories.single()
        val body = factory.bodyCalls as QueryCallbackFactoryBodyCallsWireDocument.Exhaustive
        val boundary =
            QueryCallbackFactoryBodyCallWireDocument.Boundary(
                fixture.occurrence("Seed.kt", 6, 9).callbackWire(),
                QuerySourceLessCallableWireDocument(
                    fixture.signature("external.call", emptyList()).toWireDocument(),
                    SymbolKindWireDocument.FUNCTION,
                    QuerySourceLessCallableOriginDocument.LIBRARY,
                    QuerySourceLessCallableModuleKindDocument.LIBRARY,
                    "external-library",
                ),
                QuerySourceLessCallableDispositionDocument.LIBRARY_POLICY_EXCLUDED,
            )
        val value =
            (root.copy(factories = listOf(factory.copy(bodyCalls = body.copy(calls = listOf(boundary))))).toContract()
                    as WireDocumentConversion.Converted)
                .value
        val returned =
            value.origin as io.github.amichne.kast.protocol.contract.QueryImmutableCallbackValueOriginDocument.Returned
        return io.github.amichne.kast.protocol.contract.QueryImmutableCallbackFlowDocument.create(
                returned.factory.returnedValue,
                bounded(
                    listOf(io.github.amichne.kast.protocol.contract.QueryImmutableCallbackUseDocument.Unused(value))
                ),
                bounded(emptyList()),
                io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument.EXHAUSTIVE,
            )
            .refined()
    }

    private fun observed(
        callback: io.github.amichne.kast.protocol.contract.QueryCallbackObservationDocument,
        libraries: io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument,
        execution: io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument,
    ) =
        io.github.amichne.kast.protocol.contract.QueryCallbackObservationDocument.create(
            if (execution is io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument.Observed)
                fixture.occurrence("Seed.kt", 10, 12)
            else callback.occurrence,
            callback.target,
            callback.lexicalOwner,
            if (execution is io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument.Observed)
                execution.body.occurrence
            else callback.callbackBody,
            callback.namedPolicy,
            execution,
            callback.relation,
            callback.requestedDomain,
            callback.effectiveDomain.copy(libraries = libraries),
            callback.domainFingerprint,
            callback.resultProjection,
            callback.basis,
        )

    private fun ownerFlow(
        callback: io.github.amichne.kast.protocol.contract.QueryCallbackObservationDocument,
        immutable: io.github.amichne.kast.protocol.contract.QueryImmutableCallbackFlowDocument,
    ): io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument.Observed {
        val observed = callback.flow as io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument.Observed
        val owner =
            io.github.amichne.kast.protocol.contract.QueryCallbackBodyBindingDocument(
                observed.body,
                io.github.amichne.kast.protocol.contract.QueryCallbackBodySupplyDocument.Stored,
                io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument.Unavailable(
                    io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument.STORED_CALLBACK
                ),
                bounded(emptyList()),
                io.github.amichne.kast.protocol.contract.QueryCallbackOwnerExecutionDocument.Immutable(
                    immutable,
                    io.github.amichne.kast.protocol.contract.QueryCallbackBodyDocument.Named(callback.lexicalOwner),
                ),
            )
        val inner =
            io.github.amichne.kast.protocol.contract.QueryCallbackBodyDocument.Anonymous(
                fixture.occurrence("Seed.kt", 9, 13),
                fixture.signature("anonymous@Seed.kt#9:13", emptyList()),
            )
        return observed.copy(
            body = inner,
            binding =
                io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument.Direct(
                    observed.basis,
                    fixture.occurrence("Seed.kt", 8, 14),
                    observed.body,
                ),
            invocations = bounded(emptyList()),
            scan = io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument.NOT_APPLICABLE,
            ownerBindings = bounded(listOf(owner)),
        )
    }

    private fun <T> bounded(values: List<T>) =
        io.github.amichne.kast.protocol.contract.BoundedProtocolList.create(values).refined()

    private fun <T, F> Refinement<T, F>.refined(): T = (this as Refinement.Refined).value

    private fun named() =
        QueryCallbackFactoryBodyCallWireDocument.Named(
            fixture.occurrence("Seed.kt", 6, 9).callbackWire(),
            fixture.callable("Target.kt", 0, 40, "sample.beta", emptyList()).callbackWire(),
        )
}
