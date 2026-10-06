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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class AutomaticSymbolQueryEvidenceTest : AutomaticSymbolQueryCase() {
    @Test
    fun `accumulated failures beyond a protocol collection drain in order without losing terminal limitations`() =
        runTest {
            val script = failureScript()
            val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
            val result =
                protocol.executeAutomatically(
                    request,
                    fixture.authority,
                    budget,
                    policy(retainedBytes = 128_000_000),
                ) as OperationOutcome.Qualified
            val payload = result.evidence.payload
            assertEquals(11, payload.invocation!!.accumulatedRowCount)
            assertEquals(11, payload.items.values.size)
            assertEquals(100, payload.failures.values.size)
            assertEquals(1100, payload.evidenceWindow!!.total.value)
            val reference = (payload.retention as QueryResultRetention.Retained).reference
            val last =
                readEvidence(protocol, reference, 11, payload.evidenceWindow!!.nextCursor) as OperationOutcome.Qualified
            assertEquals(0, last.evidence.payload.items.values.size)
            assertEquals(1000, last.evidence.payload.failures.values.size)
            assertEquals(QueryEvidenceWindowKind.FINAL, last.evidence.payload.evidenceWindow!!.type)
            assertNull(last.evidence.payload.nextCursor)
            assertNull(last.evidence.payload.evidenceWindow!!.nextCursor)
            assertEquals(result.qualification.limitations, last.qualification.limitations)
            assertEquals(listOf(QueryLimitationDocument.RELATION_INCOMPLETE), last.qualification.limitations)
            assertEquals(expectedFailures(), payload.failures.values + last.evidence.payload.failures.values)
            val replay =
                readEvidence(protocol, reference, 11, payload.evidenceWindow!!.nextCursor) as OperationOutcome.Qualified
            assertEquals(last, replay)
            val invalid = readEvidence(protocol, reference, 0, QueryEvidenceCursor.parse(1101).refined())
            assertEquals(
                OperationOutcome.Rejected(
                    QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.EVIDENCE_CURSOR_OUT_OF_RANGE)
                ),
                invalid,
            )
            script.assertDrained()
        }

    @Test
    fun `large complete observation sequence remains complete through independent row and evidence reads`() = runTest {
        val script = observationScript()
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val result =
            protocol.executeAutomatically(
                request,
                fixture.authority,
                budget,
                policy(1, retainedBytes = 128_000_000),
            ) as OperationOutcome.Complete
        val payload = result.evidence.payload
        assertEquals(11, payload.invocation!!.accumulatedRowCount)
        assertEquals(1100, payload.evidenceWindow!!.total.value)
        assertEquals(1, payload.relationObservations.values.size)
        val reference = (payload.retention as QueryResultRetention.Retained).reference
        val suffix =
            readEvidence(protocol, reference, payload.nextCursor!!.value, payload.evidenceWindow!!.nextCursor)
                as OperationOutcome.Complete
        assertEquals(10, suffix.evidence.payload.items.values.size)
        assertNull(suffix.evidence.payload.nextCursor)
        assertEquals(1000, suffix.evidence.payload.relationObservations.values.size)
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
    fun `evidence retention failure remains an explicit partial result without a reference`() = runTest {
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
        val result =
            protocol.executeAutomatically(request, fixture.authority, budget, policy(1)) as OperationOutcome.Qualified
        assertEquals(QueryResultRetention.CapacityExceeded, result.evidence.payload.retention)
        assertEquals(1, result.evidence.payload.failures.values.size)
        assertEquals(100, result.evidence.payload.evidenceWindow!!.total.value)
        assertInstanceOf(
            io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument.RetentionUnavailable::class.java,
            result.qualification.progress,
        )
        script.assertDrained()
    }

    private suspend fun readEvidence(
        protocol: CanonicalQueryProtocol,
        reference: QueryResultReference,
        rowCursor: Int,
        evidenceCursor: QueryEvidenceCursor?,
    ): QueryPublishedPage =
        protocol.execute(
            QueryRunRequest.ReadResult.symbols(
                reference,
                QueryResultCursor.parse(rowCursor).refined(),
                output,
                evidenceCursor = evidenceCursor,
            ),
            fixture.authority,
            budget.copy(resources = budget.resources.copy(resultLimit = ResultLimit.parse(1000).refined())),
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
