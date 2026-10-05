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
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class QueryRetainedCallbackPresentationTest {
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
    fun `retained callback evidence units preserve exact inspect refs without semantic replay`() = runTest {
        val observation = callbackObservation()
        val callbacks = observation.callbackObservations
        val units = observation.evidenceUnits()
        assertEquals(callbacks, units.flatMap { it.callbackObservations })
        val issued = issueCallbackEvidence(units)
        val protocol = callbackProtocol()
        val first =
            protocol.execute(
                QueryRunRequest.ReadResult.symbols(
                    issued.reference,
                    output = QueryOutputDocument.Symbols(bounded(emptyList())),
                ),
                fixture.authority,
                budget,
            ) as OperationOutcome.Complete
        assertEquals(0, first.evidence.payload.items.values.size)
        val projected = first.evidence.payload.relationObservations.values.flatMap { it.callbackObservations.values }
        assertEquals(listOf(1, 2, 3), projected.map { it.occurrence.range.startInclusive.value })
        assertEquals(3, projected.map { it.occurrence.candidateSelector }.distinct().size)
        assertEquals(
            callbacks.map { it.callbackBody.range.startInclusive },
            projected.map { it.callbackBody.range.startInclusive.value },
        )
        val replay =
            protocol.execute(
                QueryRunRequest.ReadResult.symbols(
                    issued.reference,
                    output = QueryOutputDocument.Symbols(bounded(emptyList())),
                ),
                fixture.authority,
                budget,
            ) as OperationOutcome.Complete
        assertEquals(first.evidence.payload.relationObservations, replay.evidence.payload.relationObservations)
        assertNull(first.evidence.payload.nextCursor)
    }

    private fun callbackObservation(): io.github.amichne.kast.query.contract.QueryRelationObservation {
        val request = RelationRequest.start(fixture.selector, RelationMeaning.Callees, fixture.budget)
        val evidence = CompilerGroundedSymbolEvidence.fromSelector(fixture.selector)
        val callbacks = callbacks(request, evidence)
        val bytes = callbacks.sumOf { it.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong() }
        val batch =
            io.github.amichne.kast.relation.contract.RelationBatch.create(
                    request,
                    emptyList(),
                    io.github.amichne.kast.relation.contract.RelationByteCount.parse(bytes).refined(),
                    io.github.amichne.kast.relation.contract.RelationWorkCount.parse(3L).refined(),
                    io.github.amichne.kast.relation.contract.RelationResultCount.parse(0).refined(),
                    callbackObservations = callbacks,
                )
                .refined()
        val complete = io.github.amichne.kast.relation.contract.RelationCompilation.complete(batch)
        val observation =
            io.github.amichne.kast.query.contract.QueryRelationObservation.from(
                io.github.amichne.kast.relation.contract.RelationReadResult.Complete(batch, complete.coverage)
            )
        org.junit.jupiter.api.Assertions.assertTrue(observation.retainedBytes >= callbacks.sumOf { it.retainedBytes })
        return observation
    }

    private fun callbacks(
        request: RelationRequest,
        evidence: CompilerGroundedSymbolEvidence,
    ): List<io.github.amichne.kast.relation.contract.RelationCallbackObservation> {
        return (1..3)
            .map { offset ->
                io.github.amichne.kast.relation.contract.RelationCallbackObservation.fromNativeBoundary(
                        request,
                        RelationOccurrence.fromBoundary(fixture.selector.file, offset, offset + 1).refined(),
                        evidence,
                        evidence,
                        RelationOccurrence.fromBoundary(fixture.selector.file, 1, 5).refined(),
                        io.github.amichne.kast.relation.contract.CallbackNamedCallPolicy.Excluded(
                            io.github.amichne.kast.relation.contract.CallbackExclusionReason.NON_INLINE_ARGUMENT,
                            RelationOccurrence.fromBoundary(fixture.selector.file, 1, 5).refined(),
                        ),
                        io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead.Unavailable(
                            io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
                                .UNRESOLVED_ARGUMENT_MAPPING
                        ),
                    )
                    .refined()
            }
            .sorted()
    }

    private fun issueCallbackEvidence(
        units: List<io.github.amichne.kast.query.contract.QueryRelationObservation>
    ): QueryResultIssuance.Issued {
        val execution =
            QueryExecutionResult.Complete.create(
                QueryResult(
                    QueryRows.Symbols.of(emptyList()),
                    emptyList(),
                    relationObservations = units,
                ),
                QueryCoverage.Complete(QueryCount.parse(0).refined()),
            )
        return store.issueResult(
            run(QueryOutputDocument.Symbols(bounded(emptyList()))),
            QueryRetainedResult.capture(fixture.authority, execution).refined(),
        ) as QueryResultIssuance.Issued
    }

    private fun callbackProtocol(): CanonicalQueryProtocol {
        return CanonicalQueryProtocol(
            QueryOperations { error("Evidence reads cannot replay native work") },
            fixture.references,
            store,
        )
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
