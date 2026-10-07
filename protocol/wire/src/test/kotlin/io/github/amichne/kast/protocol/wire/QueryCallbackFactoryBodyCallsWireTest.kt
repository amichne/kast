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
    fun `included libraries reject an excluded boundary nested in a returned callback`() {
        val callback = fixture.callbackDocument()
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
        val flow =
            io.github.amichne.kast.protocol.contract.QueryImmutableCallbackFlowDocument.create(
                    returned.factory.returnedValue,
                    bounded(
                        listOf(io.github.amichne.kast.protocol.contract.QueryImmutableCallbackUseDocument.Unused(value))
                    ),
                    bounded(emptyList()),
                    io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument.EXHAUSTIVE,
                )
                .refined()
        fun observed(libraries: io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument) =
            io.github.amichne.kast.protocol.contract.QueryCallbackObservationDocument.create(
                callback.occurrence,
                callback.target,
                callback.lexicalOwner,
                callback.callbackBody,
                callback.namedPolicy,
                io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument.Immutable(flow),
                callback.relation,
                callback.requestedDomain,
                callback.effectiveDomain.copy(libraries = libraries),
                callback.domainFingerprint,
            )
        assertInstanceOf(
            Refinement.Refined::class.java,
            observed(io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument.EXCLUDE),
        )
        assertEquals(
            Refinement.Rejected(
                io.github.amichne.kast.protocol.contract.QueryCallbackDocumentFailure.INVALID_SCAN_PROOF
            ),
            observed(io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument.INCLUDE),
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
