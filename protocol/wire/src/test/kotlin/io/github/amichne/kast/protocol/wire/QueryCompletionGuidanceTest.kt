package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactRequiredObligationDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCompletionCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionRetentionFailure
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryImpactRequiredObligationsDocument
import io.github.amichne.kast.protocol.contract.QueryInvestigationCompletionFailureDocument
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryStaticModelDocument
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class QueryCompletionGuidanceTest {
    @Test
    fun `retained evidence explains incomplete proof and preserves the next request for every output`() {
        val reference = QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001").value()
        for (output in
            listOf(
                QueryOutputDocument.Symbols(BoundedProtocolList.create(listOf(QuerySymbolFieldDocument.NAME)).value()),
                QueryOutputDocument.TraversalRecords,
                QueryOutputDocument.ValuePaths,
                QueryOutputDocument.ImpactWitness(ImpactWitnessSectionDocument.FINDINGS),
            )) {
            val rejection =
                QueryRunRejection.CompletionUnproven(
                    QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1,
                    QueryCompletionCauseDocument.IncompleteExecution,
                    QueryCompletionCoverageDocument.Complete,
                    QueryInvocationStop.WORK_LIMIT,
                    QueryCompletionEvidenceDocument.Retained(
                        reference,
                        BoundedProtocolList.create<QueryResultItemDocument>(emptyList()).value(),
                        question(output),
                    ),
                )
            val projected =
                CanonicalQueryCliDocuments.project(OperationOutcome.Rejected(rejection))
                    as ProjectedOperationOutcome.Rejected
            for (detail in ToolOutputDetail.entries) {
                val document = Json.parseToJsonElement(projected.document.present(detail).value).jsonObject
                assertEquals("rejected", document.getValue("status").jsonPrimitive.content)
                val completion = document.getValue("rejection").jsonObject.getValue("detail").jsonObject
                assertEquals(
                    "The query reached its work limit before completion. " +
                        "This does not establish that the requested data is absent.",
                    completion.getValue("explanation").jsonPrimitive.content,
                )
                val evidence = completion.getValue("evidence").jsonObject
                assertEquals(
                    "Retained output is available. " +
                        "Use nextQuery to read it without another semantic read. " +
                        "Keep the original incomplete verdict when you report these rows.",
                    evidence.getValue("instruction").jsonPrimitive.content,
                )
                val next = evidence.getValue("nextQuery").jsonObject.getValue("request").jsonObject
                assertEquals("READ_RESULT", next.getValue("type").jsonPrimitive.content)
                assertEquals(reference.value, next.getValue("result").jsonPrimitive.content)
                assertFalse(evidence.containsKey("continuation"))
            }
        }
    }

    @Test
    fun `unavailable retention never promises recoverable rows`() {
        val rejection =
            QueryRunRejection.CompletionUnproven(
                QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1,
                QueryCompletionCauseDocument.RetentionUnavailable(QueryCompletionRetentionFailure.CAPACITY_EXCEEDED),
                QueryCompletionCoverageDocument.Complete,
                QueryInvocationStop.RETENTION_FAILED,
                QueryCompletionEvidenceDocument.Unavailable(QueryCompletionRetentionFailure.CAPACITY_EXCEEDED),
            )
        val projected =
            CanonicalQueryCliDocuments.project(OperationOutcome.Rejected(rejection))
                as ProjectedOperationOutcome.Rejected
        val document = Json.parseToJsonElement(projected.document.value).jsonObject
        val detail = document.getValue("rejection").jsonObject.getValue("detail").jsonObject
        assertEquals(
            "The query did not retain its output. " + "This does not establish that the requested data is absent.",
            detail.getValue("explanation").jsonPrimitive.content,
        )
        val evidence = detail.getValue("evidence").jsonObject
        assertEquals(
            "No retained result is available. " +
                "Inspect cause and the original coverage before you select a new query. " +
                "Do not retry the same request automatically.",
            evidence.getValue("instruction").jsonPrimitive.content,
        )
        assertFalse(evidence.containsKey("nextQuery"))
        assertEquals("CAPACITY_EXCEEDED", evidence.getValue("cause").jsonPrimitive.content)
    }

    @Test
    fun `native flow proof failure preserves its obligation instead of claiming a resource limit`() {
        val rejection =
            QueryRunRejection.CompletionUnproven(
                QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1,
                QueryCompletionCauseDocument.InvestigationUnproven(
                    QueryInvestigationCompletionFailureDocument.ObligationsUnresolved(
                        QueryImpactRequiredObligationsDocument.from(
                                listOf(ImpactRequiredObligationDocument.NATIVE_FLOW)
                            )
                            .value()
                    )
                ),
                QueryCompletionCoverageDocument.Complete,
                QueryInvocationStop.TERMINAL_INCOMPLETE,
                QueryCompletionEvidenceDocument.Unavailable(QueryCompletionRetentionFailure.CAPACITY_EXCEEDED),
            )
        val projected =
            CanonicalQueryCliDocuments.project(OperationOutcome.Rejected(rejection))
                as ProjectedOperationOutcome.Rejected
        val detail =
            Json.parseToJsonElement(projected.document.value)
                .jsonObject
                .getValue("rejection")
                .jsonObject
                .getValue("detail")
                .jsonObject
        assertEquals(
            "The value investigation has an unresolved proof obligation. " +
                "Inspect investigationFailure before you change resource limits. " +
                "This does not establish that the requested data is absent.",
            detail.getValue("explanation").jsonPrimitive.content,
        )
        val cause = detail.getValue("cause").jsonObject
        assertEquals("INVESTIGATION_UNPROVEN", cause.getValue("type").jsonPrimitive.content)
        val original = cause.getValue("investigationFailure").jsonObject
        assertEquals("REQUIRED_OBLIGATIONS_UNRESOLVED", original.getValue("type").jsonPrimitive.content)
        assertEquals(listOf("NATIVE_FLOW"), original.getValue("required").jsonArray.map { it.jsonPrimitive.content })
    }

    private fun question(output: QueryOutputDocument): QueryQuestionDocument =
        QueryQuestionDocument(
            QueryFromDocument.Location(
                ProtocolText.parse("Fixture.kt").value(),
                ProtocolOffset.parse(0).value(),
            ),
            BoundedProtocolList.create<QueryStepDocument>(emptyList()).value(),
            output,
        )

    private fun <V, F> Refinement<V, F>.value(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Invalid fixture: $failure")
        }
}
