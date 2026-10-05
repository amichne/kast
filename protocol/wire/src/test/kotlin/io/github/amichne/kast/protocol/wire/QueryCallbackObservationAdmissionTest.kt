package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryCallbackCallableDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryRelationCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint
import io.github.amichne.kast.protocol.contract.QueryRelationRequestedDomainDocument
import io.github.amichne.kast.protocol.contract.QuerySemanticScopeDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationOccurrenceDocument
import io.github.amichne.kast.protocol.contract.RelationProviderDocument
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryCallbackObservationAdmissionTest {
    private val fixture = QueryCallbackWireFixture()

    @Test
    fun `observed callback validates exact identities ownership mapping and obligations before admission`() {
        val doc = fixture.callbackDocument()
        val wire = doc.toWireDocument()
        assertEquals(WireDocumentConversion.Converted(doc), wire.toContract())
        val encoded = wireJson.encodeToJsonElement(QueryCallbackObservationWireDocument.serializer(), wire).jsonObject
        val flow = encoded.getValue("flow").jsonObject
        assertEquals("OBSERVED", flow.getValue("type").jsonPrimitive.content)
        assertEquals("BOUND", flow.getValue("binding").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("0", flow.getValue("binding").jsonObject.getValue("position").jsonPrimitive.content)
        val observed = wire.flow as QueryCallbackFlowWireDocument.Observed
        val bound = observed.binding as QueryCallbackBindingWireDocument.Bound
        val named = observed.invocations.single().owner as QueryCallbackBodyWireDocument.Named
        val wrongs =
            listOf(
                wire.copy(callbackBody = wire.callbackBody.copy(range = SourceRangeWireDocument(20, 21))),
                wire.copy(
                    lexicalOwner =
                        wire.lexicalOwner.copy(declaration = wire.lexicalOwner.declaration.copy(file = "Wrong.kt"))
                ),
                wire.copy(flow = observed.copy(binding = bound.copy(position = 2))),
                wire.copy(
                    flow = observed.copy(binding = bound.copy(parameter = bound.parameter.copy(file = "Wrong.kt")))
                ),
                wire.copy(flow = observed.copy(invocations = observed.invocations + observed.invocations)),
                wire.copy(flow = observed.copy(invocations = emptyList())),
                wire.copy(
                    flow =
                        observed.copy(
                            body = observed.body.copy(occurrence = observed.body.occurrence.copy(file = "Wrong.kt"))
                        )
                ),
                wire.copy(
                    flow =
                        observed.copy(
                            invocations =
                                listOf(
                                    observed.invocations.single().copy(owner = named.copy(callable = wire.lexicalOwner))
                                )
                        )
                ),
            )
        for (wrong in wrongs) {
            // Model the transport boundary: decode the malformed shape before attempting admission.
            val raw = wireJson.encodeToString(QueryCallbackObservationWireDocument.serializer(), wrong)
            val decoded = wireJson.decodeFromString(QueryCallbackObservationWireDocument.serializer(), raw)
            assertEquals(WireDocumentConversion.Rejected, decoded.toContract(), raw)
        }
    }

    @Test
    fun `encoded nested supplying and receiving owners require explicit execution obligation`() {
        val wire = fixture.callbackDocument().toWireDocument()
        val flow = wire.flow as QueryCallbackFlowWireDocument.Observed
        val bound = flow.binding as QueryCallbackBindingWireDocument.Bound
        val nestedReceiver =
            QueryCallbackBodyWireDocument.Named(
                callbackCallableWire(fixture.callable("Boundary.kt", 10, 25, "sample.nested", emptyList()))
            )
        val variants =
            listOf(
                flow.copy(binding = bound.copy(invocationOwner = anonymousWire("Seed.kt", 1, 25))),
                flow.copy(
                    invocations = listOf(flow.invocations.single().copy(owner = anonymousWire("Boundary.kt", 10, 25)))
                ),
                flow.copy(invocations = listOf(flow.invocations.single().copy(owner = nestedReceiver))),
            )
        for (nested in variants) {
            val missing = wire.copy(flow = nested)
            val missingRaw = wireJson.encodeToString(QueryCallbackObservationWireDocument.serializer(), missing)
            val missingDecoded =
                wireJson.decodeFromString(QueryCallbackObservationWireDocument.serializer(), missingRaw)
            assertEquals(WireDocumentConversion.Rejected, missingDecoded.toContract())
            val qualified =
                wire.copy(
                    flow = nested.copy(obligations = listOf(QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION))
                )
            val qualifiedRaw = wireJson.encodeToString(QueryCallbackObservationWireDocument.serializer(), qualified)
            val qualifiedDecoded =
                wireJson.decodeFromString(QueryCallbackObservationWireDocument.serializer(), qualifiedRaw)
            assertTrue(qualifiedDecoded.toContract() is WireDocumentConversion.Converted)
        }
    }

    private fun anonymousWire(file: String, start: Int, end: Int) =
        QueryCallbackBodyWireDocument.Anonymous(
            callbackOccurrenceWire(fixture.occurrence(file, start, end)),
            fixture.signature("anonymous@$file#$start:$end", emptyList()).toWireDocument(),
        )

    private fun callbackOccurrenceWire(value: RelationOccurrenceDocument) =
        RelationOccurrenceWireDocument(
            value.candidateSelector.value,
            value.file.value,
            value.range.toWireDocument(),
        )

    private fun callbackCallableWire(value: QueryCallbackCallableDocument) =
        QueryCallbackCallableWireDocument(
            callbackOccurrenceWire(value.declaration),
            QueryExcludedCompilerTargetWireDocument(
                value.compilerTarget.file.value,
                value.compilerTarget.range.toWireDocument(),
                value.compilerTarget.name.value,
                value.compilerTarget.kind.toWireDocument(),
                value.compilerTarget.compilerEvidence.toWireDocument(),
            ),
        )

    @Test
    fun `relation observation rejects callback proof for another question domain or meaning`() {
        val callback = fixture.callbackDocument().toWireDocument()
        val original =
            QueryRelationObservationWireDocument(
                QueryReferenceWireDocument.ExactSymbol("symbol:read"),
                callback.relation,
                RelationProviderDocument.INTELLIJ_CALLEES_V2,
                callback.requestedDomain,
                callback.effectiveDomain,
                callback.domainFingerprint,
                QueryRelationCoverageDocument.Exhausted,
                emptyList(),
                listOf(callback),
            )
        assertTrue(original.toContract() is WireDocumentConversion.Converted)
        for (forged in
            listOf(
                original.copy(relation = RelationKindDocument.CALLERS),
                original.copy(requestedDomain = QueryRelationRequestedDomainDocument.RETAINED_SEED),
                original.copy(
                    domainFingerprint =
                        (QueryRelationDomainFingerprint.parse("2".repeat(64)) as Refinement.Refined).value
                ),
                original.copy(
                    effectiveDomain =
                        original.effectiveDomain.copy(
                            scope = QuerySemanticScopeDocument.ExactFile(fixture.text("Seed.kt"))
                        )
                ),
            )) {
            val raw = wireJson.encodeToString(QueryRelationObservationWireDocument.serializer(), forged)
            val decoded = wireJson.decodeFromString(QueryRelationObservationWireDocument.serializer(), raw)
            assertEquals(WireDocumentConversion.Rejected, decoded.toContract())
        }
    }
}
