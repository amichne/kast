package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryEvidenceFailureKind
import io.github.amichne.kast.protocol.contract.QueryEvidenceSummaryState
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOriginalEvidenceSummaryDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryPredicateFailureDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentOriginalOutcome
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import io.github.amichne.kast.protocol.contract.presentationPrefix
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.protocol.wire.presentation.querySemanticHeader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class QueryOriginalEvidenceSummaryWireTest {
    @Test
    fun `uncaptured original summary stays unknown through wire public output and header`() {
        val original = emptyPage()
        val encoded = encode(original)
        assertEquals(
            "UNCAPTURED",
            encoded.getValue("original_evidence_summary_state").jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertFalse(encoded.containsKey("original_evidence_summary"))
        val restored = decode(encoded)
        assertEquals(original, restored)
        assertEquals(QueryEvidenceSummaryState.Uncaptured, restored.originalEvidenceSummary)
        assertEquals(
            QueryEvidenceSummaryState.Uncaptured,
            restored.presentationPrefix(0).value().originalEvidenceSummary,
        )
        val public = publicDocument(restored)
        assertEquals(JsonNull, public.getValue("original_evidence_summary"))
        val header = querySemanticHeader(public, QuerySemanticHeaderDocumentOriginalOutcome.COMPLETE)
        assertNull(header.blockers.failureCount)
        assertNull(header.omissions.count)
        assertNull(header.presentation.evidenceTotal)
        assertEquals(0L, header.presentation.evidencePresented)
    }

    @Test
    fun `captured original counts and reasons outlive an empty presentation through every projection`() {
        val summary =
            QueryOriginalEvidenceSummaryDocument(
                offset(1),
                offset(2),
                offset(43),
                failureKinds = bounded(listOf(QueryEvidenceFailureKind.PREDICATE)),
                omissionReasons = bounded(listOf(RelationLimitationDocument.PROVIDER_STALLED)),
                predicateReasons = bounded(listOf(QueryPredicateFailureDocument.PREDICATE_UNPROVEN)),
            )
        val original = emptyPage().copy(originalEvidenceSummary = QueryEvidenceSummaryState.Captured(summary))
        val encoded = encode(original)
        val wireState = encoded.getValue("original_evidence_summary_state").jsonObject
        assertEquals("CAPTURED", wireState.getValue("type").jsonPrimitive.content)
        assertEquals("43", wireState.getValue("summary").jsonObject.getValue("evidence_count").jsonPrimitive.content)
        val restored = decode(encoded)
        assertEquals(original, restored)
        assertEquals(QueryEvidenceSummaryState.Captured(summary), restored.originalEvidenceSummary)
        val public = publicDocument(restored)
        val publicSummary = public.getValue("original_evidence_summary").jsonObject
        assertEquals("1", publicSummary.getValue("failure_count").jsonPrimitive.content)
        assertEquals("2", publicSummary.getValue("omission_count").jsonPrimitive.content)
        assertEquals("43", publicSummary.getValue("evidence_count").jsonPrimitive.content)
        assertEquals(0, public.getValue("items").jsonArray.size)
        val header = querySemanticHeader(public, QuerySemanticHeaderDocumentOriginalOutcome.COMPLETE)
        assertEquals(1L, header.blockers.failureCount)
        assertEquals(2L, header.omissions.count)
        assertEquals(43L, header.presentation.evidenceTotal)
        assertEquals(listOf("PREDICATE_UNPROVEN"), header.blockers.codes.map { it.value })
        assertEquals(listOf("PROVIDER_STALLED"), header.omissions.codes.map { it.value })
        assertEquals(0L, header.presentation.evidencePresented)
    }

    private fun emptyPage(): QueryRunResult =
        QueryRunResult(
            QueryQuestionDocument(
                QueryFromDocument.Location(ProtocolText.parse("File.kt").value(), offset(0)),
                bounded(emptyList()),
                QueryOutputDocument.Symbols(bounded(emptyList())),
            ),
            bounded(emptyList()),
            bounded(emptyList()),
        )

    private fun encode(result: QueryRunResult): JsonObject =
        (CanonicalQuerySerializers.result.encode(result, WireValueRole.RESULT) as WireValueEncoding.Encoded)
            .value
            .jsonObject

    private fun decode(document: JsonObject): QueryRunResult =
        when (val decoded = CanonicalQuerySerializers.result.decode(document, WireValueRole.RESULT)) {
            is WireDecoding.Decoded -> decoded.value
            is WireDecoding.Rejected -> error("Expected admitted original summary: ${decoded.failure}")
        }

    private fun publicDocument(result: QueryRunResult): JsonObject {
        val outcome =
            OperationOutcome.Complete(
                EvidenceEnvelope(CanonicalOperation.QUERY_RUN.id, EvidenceGeneration.parse(1).value(), result)
            )
        val projected = CanonicalQueryCliDocuments.project(outcome) as ProjectedOperationOutcome.Complete
        return Json.parseToJsonElement(projected.document.present(ToolOutputDetail.VERBOSE).value).jsonObject
    }
}

private fun offset(value: Int): ProtocolOffset = ProtocolOffset.parse(value).value()

private fun <T> bounded(values: List<T>): BoundedProtocolList<T> = BoundedProtocolList.create(values).value()

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
