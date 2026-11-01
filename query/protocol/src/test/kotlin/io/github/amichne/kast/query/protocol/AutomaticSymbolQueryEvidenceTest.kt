package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.QueryEvidenceCursor
import io.github.amichne.kast.protocol.contract.QueryEvidenceWindowKind
import io.github.amichne.kast.protocol.contract.QueryExactFailureDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryPredicateFailureDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryRelationObservation
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.symbol.contract.SymbolExactRejection
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class AutomaticSymbolQueryEvidenceTest : AutomaticSymbolQueryCase() {
    @Test
    fun `rejected accumulated failures drain in order without losing original terminal limitations`() = runTest {
        val script = failureScript()
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val rejection =
            completionRejection(
                protocol.execute(request, fixture.authority, budget, policy(retainedBytes = 128_000_000))
            )
        val evidence = retainedEvidence(rejection)
        assertEquals(11, evidence.preview.values.size)
        assertAccumulatedOriginalEvidence(rejection)
        assertEquals(
            listOf(QueryLimitationDocument.RELATION_INCOMPLETE),
            originalCoverage(rejection).limitations.values,
        )
        val first = readEvidence(protocol, evidence.result, 11, null) as OperationOutcome.Qualified
        assertEquals(0, first.evidence.payload.items.values.size)
        assertEquals(1000, first.evidence.payload.failures.values.size)
        assertEquals(1100, first.evidence.payload.evidenceWindow!!.total.value)
        val last =
            readEvidence(protocol, evidence.result, 11, first.evidence.payload.evidenceWindow!!.nextCursor)
                as OperationOutcome.Qualified
        assertEquals(0, last.evidence.payload.items.values.size)
        assertEquals(100, last.evidence.payload.failures.values.size)
        assertEquals(QueryEvidenceWindowKind.FINAL, last.evidence.payload.evidenceWindow!!.type)
        assertNull(last.evidence.payload.nextCursor)
        assertNull(last.evidence.payload.evidenceWindow!!.nextCursor)
        assertEquals(first.qualification.limitations, last.qualification.limitations)
        assertEquals(expectedFailures(), first.evidence.payload.failures.values + last.evidence.payload.failures.values)
        val interpretation =
            last.evidence.payload.interpretation
                as io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument.EvidenceOnly
        assertEquals(rejection.originalCoverage, interpretation.originalCoverage)
        val replay = readEvidence(protocol, evidence.result, 11, first.evidence.payload.evidenceWindow!!.nextCursor)
        assertEquals(last, replay)
        val invalid = readEvidence(protocol, evidence.result, 0, QueryEvidenceCursor.parse(1101).refined())
        assertEquals(
            OperationOutcome.Rejected(
                QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.EVIDENCE_CURSOR_OUT_OF_RANGE)
            ),
            invalid,
        )
        script.assertDrained()
    }

    @Test
    fun `qualified evidence can advance while the final row window remains unchanged`() = runTest {
        val script = failureScript()
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val rejection =
            completionRejection(
                protocol.execute(request, fixture.authority, budget, policy(retainedBytes = 128_000_000))
            )
        val evidence = retainedEvidence(rejection)
        val first = readEvidence(protocol, evidence.result, 11, null) as OperationOutcome.Qualified
        val last =
            readEvidence(protocol, evidence.result, 11, first.evidence.payload.evidenceWindow!!.nextCursor)
                as OperationOutcome.Qualified
        for (detail in ToolOutputDetail.entries) {
            val firstDocument = publicPage(first, detail)
            val lastDocument = publicPage(last, detail)
            assertWindow(firstDocument, "row_window", "FINAL", 11, 11, 11)
            assertWindow(firstDocument, "evidence_window", "MORE", 0, 1000, 1100)
            assertEquals(firstDocument["row_window"], lastDocument["row_window"])
            assertWindow(lastDocument, "evidence_window", "FINAL", 1000, 1100, 1100)
            assertFalse(firstDocument.containsKey("next_cursor"))
            assertFalse(lastDocument.containsKey("next_cursor"))
            assertEquals("qualified", firstDocument.getValue("status").jsonPrimitive.content)
            assertEquals(firstDocument["qualification"], lastDocument["qualification"])
        }
        script.assertDrained()
    }

    @Test
    fun `large complete observation sequence remains complete through independent row and evidence reads`() = runTest {
        val script = observationScript()
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val result =
            protocol.execute(
                request,
                fixture.authority,
                budget,
                policy(1, retainedBytes = 128_000_000),
            ) as OperationOutcome.Complete
        val payload = result.evidence.payload
        assertEquals(11, payload.invocation!!.accumulatedRowCount)
        assertEquals(1100, payload.evidenceWindow!!.total.value)
        assertEquals(1, payload.relationObservations.values.size)
        assertObservationOriginalEvidence(payload.originalEvidenceSummary)
        val reference = (payload.retention as QueryResultRetention.Retained).reference
        val suffix =
            readEvidence(protocol, reference, payload.nextCursor!!.value, payload.evidenceWindow!!.nextCursor)
                as OperationOutcome.Complete
        assertEquals(10, suffix.evidence.payload.items.values.size)
        assertNull(suffix.evidence.payload.nextCursor)
        assertEquals(1000, suffix.evidence.payload.relationObservations.values.size)
        for (detail in ToolOutputDetail.entries) {
            val initialDocument = publicPage(result, detail)
            val suffixDocument = publicPage(suffix, detail)
            assertWindow(initialDocument, "row_window", "MORE", 0, 1, 11)
            assertEquals("1", initialDocument.getValue("next_cursor").jsonPrimitive.content)
            assertWindow(suffixDocument, "row_window", "FINAL", 1, 11, 11)
            assertWindow(suffixDocument, "evidence_window", "MORE", 1, 1001, 1100)
            assertFalse(suffixDocument.containsKey("next_cursor"))
            assertEquals("complete", suffixDocument.getValue("status").jsonPrimitive.content)
        }
        val final =
            readEvidence(protocol, reference, 11, suffix.evidence.payload.evidenceWindow!!.nextCursor)
                as OperationOutcome.Complete
        assertEquals(99, final.evidence.payload.relationObservations.values.size)
        assertNull(final.evidence.payload.evidenceWindow!!.nextCursor)
        val rows = payload.items.values + suffix.evidence.payload.items.values
        assertEquals(11, rows.map { it.rowId }.distinct().size)
        val initialRead = readEvidence(protocol, reference, 0, null) as OperationOutcome.Complete
        assertEquals(rows, initialRead.evidence.payload.items.values)
        assertEquals(
            1100,
            payload.relationObservations.values.size +
                suffix.evidence.payload.relationObservations.values.size +
                final.evidence.payload.relationObservations.values.size,
        )
        script.assertDrained()
    }

    @Test
    fun `evidence retention failure preserves rejected original coverage and explicit unavailable evidence`() =
        runTest {
            val script =
                Script(
                    listOf(listOf(row)),
                    terminal = true,
                    resultForPage = { rows, _ ->
                        QueryResult(
                            QueryRows.Symbols.of(rows),
                            List(100) { QueryItemFailure.PredicateUnproven(fixture.selector) },
                        )
                    },
                )
            val protocol =
                CanonicalQueryProtocol(script.operations, fixture.references, QueryStateStore(maximumBytes = 4096))
            val rejection = completionRejection(protocol.execute(request, fixture.authority, budget, policy(1)))
            assertEquals(1, originalCoverage(rejection).knownMinimum.value)
            assertEquals(
                listOf(QueryLimitationDocument.RELATION_INCOMPLETE),
                originalCoverage(rejection).limitations.values,
            )
            assertUnavailableOriginalEvidence(rejection)
            script.assertDrained()
        }

    private fun publicPage(page: QueryPublishedPage, detail: ToolOutputDetail): JsonObject {
        val encoded = CanonicalOperationWireBindings.queryRun.encodeOutcome(page) as WireEncoding.Encoded
        val restored =
            (CanonicalOperationWireBindings.queryRun.decodeOutcome(encoded.document) as WireDecoding.Decoded).value
        val projected = CanonicalQueryCliDocuments.project(restored)
        val document =
            when (projected) {
                is ProjectedOperationOutcome.Complete -> projected.document
                is ProjectedOperationOutcome.Qualified -> projected.document
                is ProjectedOperationOutcome.Rejected -> error("Retained page unexpectedly rejected")
            }
        return Json.parseToJsonElement(document.present(detail).value).jsonObject
    }

    private fun assertWindow(document: JsonObject, field: String, type: String, start: Int, end: Int, total: Int) {
        val window = assertInstanceOf(JsonObject::class.java, document[field], "Missing $field")
        assertEquals(setOf("type", "start", "end", "total"), window.keys)
        assertEquals(type, window.getValue("type").jsonPrimitive.content)
        assertEquals(start.toString(), window.getValue("start").jsonPrimitive.content)
        assertEquals(end.toString(), window.getValue("end").jsonPrimitive.content)
        assertEquals(total.toString(), window.getValue("total").jsonPrimitive.content)
    }

    private suspend fun readEvidence(
        protocol: CanonicalQueryProtocol,
        reference: QueryResultReference,
        rowCursor: Int,
        evidenceCursor: QueryEvidenceCursor?,
    ): QueryPublishedPage =
        protocol.executePage(
            QueryRunRequest.ReadResult.symbols(
                reference,
                QueryResultCursor.parse(rowCursor).refined(),
                output,
                evidenceCursor = evidenceCursor,
            ),
            fixture.authority,
            budget.copy(resources = budget.resources.bounded().copy(resultLimit = ResultLimit.parse(1000).refined())),
        )

    private fun failureScript() =
        Script(
            List(11) { listOf(row) },
            terminal = true,
            resultForPage = { rows, page ->
                QueryResult(
                    QueryRows.Symbols.of(rows),
                    List(100) {
                        if (page % 2 == 1) QueryItemFailure.PredicateUnproven(fixture.selector)
                        else
                            QueryItemFailure.ExactReference(
                                fixture.selector,
                                SymbolExactRejection.COMPILER_IDENTITY_UNAVAILABLE,
                            )
                    },
                )
            },
        )

    private fun expectedFailures(): List<QueryItemFailureDocument> =
        (1..11).flatMap { page ->
            List(100) {
                if (page % 2 == 1)
                    QueryItemFailureDocument.Predicate(
                        QueryReferenceDocument.ExactSymbol(fixture.exact),
                        QueryPredicateFailureDocument.PREDICATE_UNPROVEN,
                    )
                else
                    QueryItemFailureDocument.ExactReference(
                        QueryReferenceDocument.ExactSymbol(fixture.exact),
                        QueryExactFailureDocument.COMPILER_IDENTITY_UNAVAILABLE,
                    )
            }
        }

    private fun observationScript(): Script {
        val observation = completeObservation()
        return Script(
            List(11) { listOf(row) },
            resultForPage = { rows, _ ->
                QueryResult(QueryRows.Symbols.of(rows), emptyList(), relationObservations = List(100) { observation })
            },
        )
    }

    private fun completeObservation(): QueryRelationObservation {
        val request = RelationRequest.start(fixture.selector, RelationMeaning.Callees, fixture.budget)
        val batch =
            RelationBatch.create(
                    request,
                    emptyList(),
                    RelationByteCount.parse(0).refined(),
                    RelationWorkCount.parse(0).refined(),
                    RelationResultCount.parse(0).refined(),
                )
                .refined()
        return QueryRelationObservation.from(
            RelationReadResult.Complete(batch, RelationCompilation.complete(batch).coverage)
        )
    }
}
