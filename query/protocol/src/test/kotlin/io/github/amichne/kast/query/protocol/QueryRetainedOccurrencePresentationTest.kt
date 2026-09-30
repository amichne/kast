package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.RelationFactCoverageDocument
import io.github.amichne.kast.protocol.contract.RelationReferenceContextDocument
import io.github.amichne.kast.protocol.contract.RelationReferenceOwnershipDocument
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryOccurrence
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.relation.contract.RelationConfirmedReferenceTarget
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationReferenceContext
import io.github.amichne.kast.relation.contract.RelationReferenceOccurrence
import io.github.amichne.kast.relation.contract.RelationReferenceOwnership
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class QueryRetainedOccurrencePresentationTest {
    private val fixture = RelationPagingFixture.published()
    private val store = QueryStateStore(clock = { 0L })
    private val budget =
        QueryBudget(
            ResourceBudget(
                ResultLimit.parse(100).refined(),
                WorkUnitLimit.parse(100).refined(),
                ElapsedTimeLimitMillis.parse(1000).refined(),
            ),
            QueryByteLimit.parse(100_000).refined(),
        )

    @Test
    fun `retained file scoped occurrences page and replay without semantic execution or invented owners`() = runTest {
        // Compiler-confirmed facts are starting inputs. This case proves detached presentation, not compiler
        // resolution.
        val occurrences = (0 until 101).map(::confirmedOccurrence)
        val execution =
            QueryExecutionResult.Complete(
                QueryResult(QueryRows.Occurrences.of(occurrences.map(QueryOccurrence::Reference)), emptyList()),
                QueryCoverage.Complete(QueryCount.parse(101).refined()),
            )
        val retained = QueryRetainedResult.capture(fixture.authority, execution).refined()
        val issued = store.issueResult(run(), retained) as QueryResultIssuance.Issued
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("Retained occurrence presentation must not execute semantic work") },
                fixture.references,
                store,
            )
        val request = QueryRunRequest.ReadResult.occurrences(issued.reference)
        val first = protocol.execute(request, fixture.authority, budget) as OperationOutcome.Complete
        val replay = protocol.execute(request, fixture.authority, budget) as OperationOutcome.Complete
        assertEquals(first.evidence.payload.items, replay.evidence.payload.items)
        assertEquals(100, first.evidence.payload.items.values.size)
        assertEquals(100, first.evidence.payload.nextCursor?.value)
        val last =
            protocol.execute(
                QueryRunRequest.ReadResult.occurrences(issued.reference, QueryResultCursor.parse(100).refined()),
                fixture.authority,
                budget,
            ) as OperationOutcome.Complete
        assertNull(last.evidence.payload.nextCursor)
        val items =
            (first.evidence.payload.items.values + last.evidence.payload.items.values).map {
                it as QueryResultItemDocument.ReferenceOccurrence
            }
        assertEquals(issued.rowIds, items.map { it.rowId })
        assertEquals(101, items.map { it.rowId }.toSet().size)
        assertEquals(
            (0 until 101).map { 8 + it * 2 },
            items.map { it.occurrence.occurrence.range.startInclusive.value },
        )
        assertFileScopedEvidence(items)
        assertSymbolPresentationRejected(protocol, issued)
        val restored = store.restoreResult(issued.reference, fixture.authority) as QueryResultRestoration.Restored
        assertEquals(
            occurrences,
            (restored.result as QueryRetainedResult.Occurrences).occurrences.map {
                (it as QueryOccurrence.Reference).value
            },
        )
        assertEquals(issued.rowIds, restored.rowIds)
    }

    private suspend fun assertSymbolPresentationRejected(
        protocol: CanonicalQueryProtocol,
        issued: QueryResultIssuance.Issued,
    ) {
        val wrong =
            protocol.execute(
                QueryRunRequest.ReadResult.symbols(
                    issued.reference,
                    output = QueryOutputDocument.Symbols(bounded(emptyList())),
                ),
                fixture.authority,
                budget,
            ) as OperationOutcome.Rejected
        assertEquals(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.RESULT_FIELD_UNAVAILABLE),
            wrong.reason,
        )
    }

    private fun assertFileScopedEvidence(items: List<QueryResultItemDocument.ReferenceOccurrence>) {
        items.forEachIndexed { index, item ->
            val context =
                if (index % 2 == 0) RelationReferenceContextDocument.IMPORT
                else RelationReferenceContextDocument.ALIASED_IMPORT
            assertEquals(context, item.occurrence.context)
            assertEquals(RelationReferenceOwnershipDocument.FileScoped(context), item.occurrence.ownership)
            assertEquals(RelationFactCoverageDocument.EXACT_COMPILER_CONFIRMED, item.occurrence.coverage)
        }
    }

    private fun confirmedOccurrence(index: Int): RelationReferenceOccurrence {
        val request =
            RelationRequest.start(
                fixture.selector,
                RelationMeaning.References,
                fixture.budget,
                RelationSearchBoundary.WORKSPACE_EXPANSION,
            )
        val target =
            RelationConfirmedReferenceTarget.fromCompiler(request.subject, request.subject.compilerIdentity).refined()
        val context = if (index % 2 == 0) RelationReferenceContext.IMPORT else RelationReferenceContext.ALIASED_IMPORT
        return RelationReferenceOccurrence.confirmed(
                request,
                target,
                RelationOccurrence.fromBoundary(request.subject.file, 8 + index * 2, 9 + index * 2).refined(),
                context,
                RelationReferenceOwnership.FileScoped(context),
                RelationProvenance.K2_AUTHORED_SOURCE,
            )
            .refined()
    }

    private fun run(output: QueryOutputDocument = QueryOutputDocument.Occurrences) =
        QueryRunRequest.Run(
            QueryFromDocument.Symbols(
                QueryDiscoveryDocument(
                    QueryMatchDocument.All,
                    QueryScopeDocument(bounded(listOf(ProtocolText.parse("main").refined())), null, null),
                    bounded(listOf(QueryDeclarationKindDocument.FUNCTION)),
                )
            ),
            bounded(emptyList()),
            output,
            QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
        )

    private fun <Value> bounded(values: List<Value>) = BoundedProtocolList.create(values).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejection: $failure")
        }
}
