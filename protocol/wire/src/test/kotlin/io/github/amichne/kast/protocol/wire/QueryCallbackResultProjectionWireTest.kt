package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryCallbackResultProjectionWireTest {
    private val fixture = QueryCallbackWireFixture()

    @Test
    fun `each result site encodes its closed kind and required payload`() {
        val wire = fixture.callbackDocument().toWireDocument()
        val flow = wire.flow as QueryCallbackFlowWireDocument.Observed
        val invocation = flow.invocations.single()
        val direct = QueryCallbackBindingWireDocument.Direct(flow.basis, invocation.occurrence, invocation.owner)
        val cases =
            listOf(
                Triple(QueryCallbackResultSiteWireDocument.Occurrence(wire.occurrence), "OCCURRENCE", "occurrence"),
                Triple(QueryCallbackResultSiteWireDocument.Invocation(invocation), "INVOCATION", "invocation"),
                Triple(QueryCallbackResultSiteWireDocument.DirectInvocation(direct), "DIRECT_INVOCATION", "binding"),
                Triple(QueryCallbackResultSiteWireDocument.Target(wire.target), "TARGET", "target"),
                Triple(QueryCallbackResultSiteWireDocument.AnonymousTarget(flow.body), "ANONYMOUS_TARGET", "body"),
            )
        for ((site, kind, payload) in cases) {
            val encoded =
                wireJson.encodeToJsonElement(QueryCallbackResultSiteWireDocument.serializer(), site).jsonObject
            assertEquals(setOf("type", payload), encoded.keys)
            assertEquals(JsonPrimitive(kind), encoded.getValue("type"))
            assertEquals(
                site,
                wireJson.decodeFromString(QueryCallbackResultSiteWireDocument.serializer(), encoded.toString()),
            )
        }
    }

    @Test
    fun `admitted projection encodes its complete frame and exact target`() {
        val value = fixture.callbackDocument()
        val wire = value.toWireDocument()
        val encoded = wireJson.encodeToJsonElement(QueryCallbackObservationWireDocument.serializer(), wire).jsonObject
        val projection = encoded.getValue("result_projection").jsonObject
        assertEquals(setOf("type", "subject", "basis", "domain", "relation", "site"), projection.keys)
        assertEquals(JsonPrimitive("ADMITTED"), projection.getValue("type"))
        assertEquals(setOf("token"), projection.getValue("subject").jsonObject.keys)
        assertEquals(JsonPrimitive("exact:seed"), projection.getValue("subject").jsonObject.getValue("token"))
        assertEquals(JsonPrimitive("1".repeat(64)), projection.getValue("domain"))
        assertEquals(JsonPrimitive("callees"), projection.getValue("relation"))
        assertEquals(encoded.getValue("target"), projection.getValue("site").jsonObject.getValue("target"))
        assertEquals(JsonPrimitive("TARGET"), projection.getValue("site").jsonObject.getValue("type"))
        assertEquals(WireDocumentConversion.Converted(value), wire.toContract())
    }

    @Test
    fun `unproven projection encodes its finite cause without success fields`() {
        val wire =
            fixture
                .callbackDocument()
                .toWireDocument()
                .copy(
                    resultProjection =
                        QueryCallbackResultProjectionWireDocument.Unproven(
                            QueryCallbackFlowCauseDocument.OUTSIDE_DOMAIN
                        )
                )
        val encoded = wireJson.encodeToJsonElement(QueryCallbackObservationWireDocument.serializer(), wire).jsonObject
        val projection = encoded.getValue("result_projection").jsonObject
        assertEquals(setOf("type", "cause"), projection.keys)
        assertEquals(JsonPrimitive("UNPROVEN"), projection.getValue("type"))
        assertEquals(JsonPrimitive("OUTSIDE_DOMAIN"), projection.getValue("cause"))
        assertEquals(
            wire,
            wireJson.decodeFromString(QueryCallbackObservationWireDocument.serializer(), encoded.toString()),
        )
    }

    @Test
    fun `wire admission rejects a substituted domain basis target or direction`() {
        val wire = fixture.callbackDocument().toWireDocument()
        val admitted = wire.resultProjection as QueryCallbackResultProjectionWireDocument.Admitted
        val foreignBasis =
            ImpactSemanticBasisDocument.Published(
                fixture.text("/workspace"),
                (ImpactEvidenceRevisionDocument.parse(2L) as Refinement.Refined).value,
            )
        val foreignDomain = (QueryRelationDomainFingerprint.parse("3".repeat(64)) as Refinement.Refined).value
        for (projection in
            listOf(
                admitted.copy(domain = foreignDomain),
                admitted.copy(basis = foreignBasis),
                admitted.copy(site = QueryCallbackResultSiteWireDocument.Target(wire.lexicalOwner)),
                admitted.copy(relation = RelationKindDocument.CALLERS),
            )) {
            assertEquals(WireDocumentConversion.Rejected, wire.copy(resultProjection = projection).toContract())
        }
    }

    @Test
    fun `decoder rejects missing proof fields and unknown proof or site kinds`() {
        val wire = fixture.callbackDocument().toWireDocument()
        val encoded = wireJson.encodeToString(QueryCallbackObservationWireDocument.serializer(), wire)
        val malformed =
            listOf(
                encoded.substringBefore(",\"result_projection\"") + "}",
                encoded.replace("\"ADMITTED\"", "\"UNKNOWN\""),
                encoded.replace("\"TARGET\"", "\"UNKNOWN\""),
                encoded.replace("\"subject\":{\"token\":\"exact:seed\"},", ""),
                encoded.replace("\"subject\":{\"token\":\"exact:seed\"}", "\"subject\":\"${"2".repeat(64)}\""),
                encoded.replace("\"domain\":\"${"1".repeat(64)}\",", ""),
                encoded.replace("\"token\":\"exact:seed\"", "\"token\":null"),
            )
        for (input in malformed) {
            assertThrows(SerializationException::class.java) {
                wireJson.decodeFromString(QueryCallbackObservationWireDocument.serializer(), input)
            }
        }
    }
}
