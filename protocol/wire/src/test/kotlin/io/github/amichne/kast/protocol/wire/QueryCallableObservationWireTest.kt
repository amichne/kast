package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryCallableObservationDocument
import io.github.amichne.kast.protocol.contract.QueryCallableTargetDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBodyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackParameterIdentityDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryRelationLimitationsDocument
import io.github.amichne.kast.protocol.contract.QueryRelationObservationDocument
import io.github.amichne.kast.protocol.contract.QuerySourceLessCallableDispositionDocument
import io.github.amichne.kast.protocol.contract.QuerySourceLessCallableDocument
import io.github.amichne.kast.protocol.contract.QuerySourceLessCallableModuleKindDocument
import io.github.amichne.kast.protocol.contract.QuerySourceLessCallableOriginDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationProviderDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryCallableObservationWireTest {
    private val fixture = QueryCallbackWireFixture()

    @Test
    fun `exact parameter invocation encodes formal identity and anonymous invocation ownership`() {
        val value = parameterObservation()
        val wire = value.toWireDocument()
        val encoded = wireJson.encodeToJsonElement(QueryCallableObservationWireDocument.serializer(), wire).jsonObject
        assertEquals(setOf("occurrence", "lexical_owner", "body", "target"), encoded.keys)
        assertEquals(JsonPrimitive("ANONYMOUS"), encoded.getValue("body").jsonObject.getValue("type"))
        val target = encoded.getValue("target").jsonObject
        assertEquals(setOf("type", "parameter"), target.keys)
        assertEquals(JsonPrimitive("PARAMETER_INVOCATION"), target.getValue("type"))
        assertEquals(setOf("callable", "position", "parameter"), target.getValue("parameter").jsonObject.keys)
        assertEquals(JsonPrimitive(0), target.getValue("parameter").jsonObject.getValue("position"))
        assertEquals(WireDocumentConversion.Converted(value), wire.toContract())
        val admitted = wire.target as QueryCallableTargetWireDocument.ParameterInvocation
        for (invalid in
            listOf(
                admitted.parameter.copy(position = -1),
                admitted.parameter.copy(position = 1),
                admitted.parameter.copy(
                    parameter =
                        RelationOccurrenceWireDocument(
                            "candidate:foreign",
                            "Elsewhere.kt",
                            SourceRangeWireDocument(8, 15),
                        )
                ),
            )) {
            assertEquals(
                WireDocumentConversion.Rejected,
                wire.copy(target = QueryCallableTargetWireDocument.ParameterInvocation(invalid)).toContract(),
            )
        }
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(
                QueryCallableObservationWireDocument.serializer(),
                encoded.toString().replace("PARAMETER_INVOCATION", "UNKNOWN"),
            )
        }
    }

    @Test
    fun `source-less callable retains compiler signature origin module and finite disposition without source claims`() {
        val value = boundaryObservation()
        val wire = value.toWireDocument()
        val encoded = wireJson.encodeToJsonElement(QueryCallableObservationWireDocument.serializer(), wire).jsonObject
        val target = encoded.getValue("target").jsonObject
        assertEquals(setOf("type", "callable", "disposition"), target.keys)
        assertEquals(JsonPrimitive("SOURCE_LESS"), target.getValue("type"))
        assertEquals(JsonPrimitive("LIBRARY_POLICY_EXCLUDED"), target.getValue("disposition"))
        assertEquals(
            setOf("compiler_evidence", "kind", "origin", "module_kind", "module_name"),
            target.getValue("callable").jsonObject.keys,
        )
        assertEquals(JsonPrimitive("LIBRARY"), target.getValue("callable").jsonObject.getValue("origin"))
        assertEquals(WireDocumentConversion.Converted(value), wire.toContract())
        val proof = wire.target as QueryCallableTargetWireDocument.SourceLess
        for (invalid in
            listOf(
                proof.callable.copy(origin = QuerySourceLessCallableOriginDocument.SOURCE),
                proof.callable.copy(moduleName = "\n"),
                proof.callable.copy(kind = SymbolKindWireDocument.CLASSLIKE),
            )) {
            assertEquals(
                WireDocumentConversion.Rejected,
                wire.copy(target = proof.copy(callable = invalid)).toContract(),
            )
        }
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(
                QueryCallableObservationWireDocument.serializer(),
                encoded.toString().replace("LIBRARY_POLICY_EXCLUDED", "UNKNOWN"),
            )
        }
    }

    @Test
    fun `relation coverage cannot bless a missing library source or forged boundary policy`() {
        val callback = fixture.callbackDocument()
        val document = boundaryRelationObservation()
        val wire = document.toWireDocument()
        assertTrue(wire.toContract() is WireDocumentConversion.Converted)
        assertEquals(WireDocumentConversion.Rejected, wire.copy(relation = RelationKindDocument.CALLERS).toContract())
        assertEquals(
            WireDocumentConversion.Rejected,
            wire
                .copy(
                    effectiveDomain =
                        callback.effectiveDomain.copy(libraries = QueryDiscoveryInclusionPolicyDocument.INCLUDE)
                )
                .toContract(),
        )
        val callable = wire.callableObservations.single()
        val target = callable.target as QueryCallableTargetWireDocument.SourceLess
        val missing =
            callable.copy(
                target =
                    target.copy(disposition = QuerySourceLessCallableDispositionDocument.LIBRARY_SOURCE_UNAVAILABLE)
            )
        val including =
            wire.copy(
                effectiveDomain =
                    callback.effectiveDomain.copy(libraries = QueryDiscoveryInclusionPolicyDocument.INCLUDE),
                callableObservations = listOf(missing),
            )
        assertEquals(WireDocumentConversion.Rejected, including.toContract())
        assertTrue(
            including
                .copy(
                    coverage =
                        QueryRelationCoverageDocument.TerminalIncomplete(
                            QueryRelationLimitationsDocument.from(listOf(RelationLimitationDocument.UNSUPPORTED_ITEM))
                                .refined()
                        )
                )
                .toContract() is WireDocumentConversion.Converted
        )
    }

    private fun boundaryRelationObservation(): QueryRelationObservationDocument {
        val callback = fixture.callbackDocument()
        return QueryRelationObservationDocument(
            QueryReferenceDocument.ExactSymbol(fixture.text("symbol:seed")),
            RelationKindDocument.CALLEES,
            RelationProviderDocument.INTELLIJ_CALLEES_V2,
            callback.requestedDomain,
            callback.effectiveDomain,
            callback.domainFingerprint,
            QueryRelationCoverageDocument.Exhausted,
            bounded(emptyList()),
            bounded(emptyList()),
            bounded(listOf(boundaryObservation())),
        )
    }

    private fun parameterObservation(): QueryCallableObservationDocument {
        val owner = fixture.callable("Seed.kt", 0, 60, "sample.outer", listOf("kotlin.Function0<kotlin.Unit>"))
        val body =
            QueryCallbackBodyDocument.Anonymous(
                fixture.occurrence("Seed.kt", 16, 40),
                fixture.signature("anonymous@Seed.kt#16:40", emptyList()),
            )
        return QueryCallableObservationDocument.create(
                fixture.occurrence("Seed.kt", 20, 28),
                owner,
                body,
                QueryCallableTargetDocument.ParameterInvocation(
                    QueryCallbackParameterIdentityDocument(
                        owner,
                        ProtocolOffset.parse(0).refined(),
                        fixture.occurrence("Seed.kt", 8, 15),
                    )
                ),
            )
            .refined()
    }

    private fun boundaryObservation(): QueryCallableObservationDocument {
        val owner = fixture.callbackDocument().lexicalOwner
        val callable =
            QuerySourceLessCallableDocument.create(
                    fixture.signature("external.consume", emptyList()),
                    SymbolKindDocument.FUNCTION,
                    QuerySourceLessCallableOriginDocument.LIBRARY,
                    QuerySourceLessCallableModuleKindDocument.LIBRARY,
                    fixture.text("external-lib"),
                )
                .refined()
        return QueryCallableObservationDocument.create(
                fixture.occurrence("Seed.kt", 20, 28),
                owner,
                QueryCallbackBodyDocument.Named(owner),
                QueryCallableTargetDocument.SourceLess(
                    callable,
                    QuerySourceLessCallableDispositionDocument.LIBRARY_POLICY_EXCLUDED,
                ),
            )
            .refined()
    }

    private fun <V> bounded(values: List<V>) = BoundedProtocolList.create(values).refined()

    private fun <V, F> Refinement<V, F>.refined(): V = (this as Refinement.Refined).value
}
