package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.LiveReadContentView
import io.github.amichne.kast.kernel.LiveReadEvidence
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryExactFailureDocument
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument
import io.github.amichne.kast.query.protocol.QueryInlinePresentation
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedQueryPresentationHeaderTest {
    @Test
    fun `oversized question preserves complete header and retained authority without a new store`() {
        val semantic = retainedQuestion()
        assertEquals(
            QueryInlinePresentation.RETENTION_REQUIRED,
            hostedInlineQueryPresentation(semantic, ReadLimits.Default, allowance()),
        )
        val document = encodeHeader(semantic)
        val header = document.getValue("semantic").jsonObject
        assertEquals("QUERY_PRESENTATION_HEADER", document.getValue("type").jsonPrimitive.content)
        assertEquals("complete", header.getValue("original_outcome").jsonPrimitive.content)
        assertEquals("COMPLETE", header.getValue("coverage").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("READ_RESULT", header.getValue("recovery").jsonObject.getValue("type").jsonPrimitive.content)
        assertZeroPresented(header)
        assertEquals(
            header.getValue("recovery").jsonObject.getValue("cursor"),
            document.getValue("nextQuery").jsonObject.getValue("request").jsonObject.getValue("cursor"),
        )
        assertTrue(!document.containsKey("question"))
    }

    @Test
    fun `header keeps a qualified original failure without claiming a delivered row or evidence`() {
        val semantic = retainedQuestion()
        val row = semantic.evidence.payload.items.values.single() as QueryResultItemDocument.ExactSymbol
        val failure =
            QueryItemFailureDocument.ExactReference(row.ref, QueryExactFailureDocument.COMPILER_IDENTITY_UNAVAILABLE)
        val qualified =
            OperationOutcome.Qualified(
                semantic.evidence.copy(payload = semantic.evidence.payload.copy(failures = bounded(listOf(failure)))),
                QueryRunQualification.create(
                        QueryKnownMinimum.parse(1).refined(),
                        listOf(QueryLimitationDocument.REFINEMENT_INCOMPLETE),
                        QueryQualifiedProgressDocument.TerminalIncomplete(
                            QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE
                        ),
                    )
                    .refined(),
            )
        val payload = qualified.evidence.payload
        val captured =
            qualified.copy(
                evidence =
                    qualified.evidence.copy(
                        payload =
                            payload.copy(
                                originalEvidenceSummary =
                                    io.github.amichne.kast.protocol.contract.QueryEvidenceSummaryState.Captured(
                                        io.github.amichne.kast.protocol.contract.QueryOriginalEvidenceSummaryDocument
                                            .from(payload)
                                    )
                            )
                    )
            )
        val header = encodeHeader(captured).getValue("semantic").jsonObject
        assertEquals("qualified", header.getValue("original_outcome").jsonPrimitive.content)
        assertEquals("1", header.getValue("blockers").jsonObject.getValue("failure_count").jsonPrimitive.content)
        assertZeroPresented(header)
        val unknown = encodeHeader(qualified).getValue("semantic").jsonObject.getValue("blockers").jsonObject
        assertEquals("null", unknown.getValue("failure_count").jsonPrimitive.content)
        assertTrue(unknown.getValue("codes").toString().contains("compiler-identity-unavailable"))
    }

    @Test
    fun `unretained original outcome has explicit detail unavailability and no usable selector`() {
        val semantic = retainedQuestion()
        val unretained =
            semantic.copy(
                evidence =
                    semantic.evidence.copy(
                        payload =
                            semantic.evidence.payload.copy(
                                retention = QueryResultRetention.NotRequested,
                                presentationWindow = null,
                                nextCursor = null,
                            )
                    )
            )
        val document = encodeHeader(unretained)
        assertEquals(JsonNull, document.getValue("nextQuery"))
        val header = document.getValue("semantic").jsonObject
        assertEquals("complete", header.getValue("original_outcome").jsonPrimitive.content)
        assertEquals("UNAVAILABLE", header.getValue("recovery").jsonObject.getValue("type").jsonPrimitive.content)
        assertZeroPresented(header)
    }

    private fun retainedQuestion(): OperationOutcome.Complete<QueryRunResult> {
        val original = HostedQueryRetainedPresentationTest().page(0, 1, 1)
        val question =
            original.evidence.payload.question.copy(
                steps = bounded<QueryStepDocument>(List(300) { QueryStepDocument.Distinct })
            )
        val live =
            LiveReadEvidence.create(
                    "/workspace",
                    UUID.fromString("00000000-0000-0000-0000-000000000003"),
                    7,
                    LiveReadContentView.SAVED_PSI_COMMITTED,
                    1,
                )
                .refined()
        return original.copy(
            evidence =
                original.evidence.copy(
                    basis = EvidenceBasis.Live(live),
                    payload = original.evidence.payload.copy(question = question),
                )
        )
    }

    private fun encodeHeader(semantic: HostedQueryOutcome): JsonObject {
        val response =
            encodeHostedQueryResponse(
                semantic,
                maximumBytes = allowance(),
                retain = { error("A header must use existing retained authority") },
            )
        assertInstanceOf(HostedResponse.Canonical::class.java, response)
        assertTrue(response.document.toByteArray(Charsets.UTF_8).size <= 4096)
        return Json.parseToJsonElement(response.document).jsonObject
    }

    private fun assertZeroPresented(header: JsonObject) {
        val presentation = header.getValue("presentation").jsonObject
        assertEquals("0", presentation.getValue("rows").jsonPrimitive.content)
        assertEquals("0", presentation.getValue("evidence_presented").jsonPrimitive.content)
    }

    private fun allowance() = ReturnedByteLimit.parse(4096).refined()

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).refined()

    private fun <T, F> Refinement<T, F>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Invalid header fixture: $failure")
        }
}
