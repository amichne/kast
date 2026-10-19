package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QuerySourceInterpretationWireTest {
    @Test
    fun `wire and public projections preserve the source relationship interpretation`() {
        val reference = QueryReferenceDocument.ExactSymbol(ProtocolText.parse("exact:v2:opaque").refined())
        val result =
            QueryRunResult(
                question =
                    QueryQuestionDocument(
                        QueryFromDocument.References(bounded(listOf(reference))),
                        bounded(listOf(QueryStepDocument.Trace(QueryExpansionScopeDocument.Workspace))),
                        QueryOutputDocument.Symbols(bounded(listOf(QuerySymbolFieldDocument.NAME))),
                    ),
                items = bounded(emptyList()),
                failures = bounded(emptyList()),
                interpretation = QueryResultInterpretationDocument.SourceRelationships(),
            )
        val encoded = CanonicalQuerySerializers.result.encode(result, WireValueRole.RESULT) as WireValueEncoding.Encoded
        assertInterpretation(encoded.value.jsonObject)
        val decoded =
            CanonicalQuerySerializers.result.decode(encoded.value, WireValueRole.RESULT) as WireDecoding.Decoded
        assertEquals(result, decoded.value)
        val projected =
            CanonicalQueryCliDocuments.project(
                OperationOutcome.Complete(
                    EvidenceEnvelope(
                        CanonicalOperation.QUERY_RUN.id,
                        EvidenceBasis.Published(EvidenceGeneration.parse(1).refined()),
                        result,
                    )
                )
            ) as ProjectedOperationOutcome.Complete
        for (detail in ToolOutputDetail.entries) {
            assertInterpretation(Json.parseToJsonElement(projected.document.present(detail).value).jsonObject)
        }
    }

    private fun assertInterpretation(document: JsonObject) {
        val interpretation = document.getValue("interpretation").jsonObject
        assertEquals(setOf("type", "callbackEffects"), interpretation.keys)
        assertEquals("SOURCE_RELATIONSHIPS", interpretation.getValue("type").jsonPrimitive.content)
        assertEquals("EVIDENCE_ONLY", interpretation.getValue("callbackEffects").jsonPrimitive.content)
    }

    private fun <V> bounded(values: List<V>) = BoundedProtocolList.create(values).refined()

    private fun <V> Refinement<V, *>.refined(): V = (this as Refinement.Refined).value
}
