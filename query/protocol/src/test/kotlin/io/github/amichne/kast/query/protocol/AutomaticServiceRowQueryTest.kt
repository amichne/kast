package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.QueryBindingNameDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryJoinModeDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.service.QueryNanoClock
import io.github.amichne.kast.query.service.QueryService
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.ExactSymbolRequest
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolExactOperations
import io.github.amichne.kast.symbol.contract.SymbolResolutionRequest
import io.github.amichne.kast.symbol.contract.SymbolResolutionResult
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalDepth
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierLimit
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.traversal.contract.TraversalPage
import io.github.amichne.kast.traversal.contract.TraversalProgress
import io.github.amichne.kast.traversal.contract.TraversalRecord
import io.github.amichne.kast.traversal.contract.TraversalResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/** Production scheduler; external semantic observations are scripted. */
internal class AutomaticServiceRowQueryTest : AutomaticSymbolQueryCase() {
    @Test
    fun `production service answers do not depend on row page grants`() = runTest {
        for (output in
            listOf(
                QueryOutputDocument.Occurrences,
                QueryOutputDocument.TraversalRecords,
                QueryOutputDocument.BindingRows,
            )) {
            val small = ServiceCase(output, 1).drain()
            val large = ServiceCase(output, 3).drain()
            assertEquals(small, large, "Semantic answer changed with page grant: $output")
            val count =
                when (output) {
                    QueryOutputDocument.Occurrences -> 4
                    QueryOutputDocument.BindingRows -> 3
                    QueryOutputDocument.TraversalRecords -> 1
                    is QueryOutputDocument.Symbols,
                    QueryOutputDocument.ValuePaths,
                    is QueryOutputDocument.ImpactWitness -> error("Invalid case")
                }
            assertEquals(count, small.size)
        }
    }

    private inner class ServiceCase(private val output: QueryOutputDocument, limit: Int) {
        private val state = QueryStateStore()
        private var nativeCalls = 0
        private val service =
            QueryService(
                discovery = SymbolDiscoveryOperations { error("Unexpected discovery") },
                exact =
                    object : SymbolExactOperations {
                        override suspend fun resolve(request: SymbolResolutionRequest): SymbolResolutionResult =
                            error("Unexpected resolution")

                        override suspend fun describe(request: ExactSymbolRequest): SymbolDescriptionResult {
                            nativeCalls++
                            return SymbolDescriptionResult.Described(SymbolDescription.from(request.selector))
                        }
                    },
                source = SourceReadOperations { error("Unexpected source read") },
                relations =
                    RelationOperations { child ->
                        nativeCalls++
                        fixture.operations.read(child)
                    },
                traversal =
                    TraversalOperations { plan ->
                        nativeCalls++
                        val child =
                            RelationRequest.start(
                                fixture.selector,
                                RelationMeaning.References,
                                plan.budget.oneHop,
                            )
                        val facts =
                            (0..0).map { index ->
                                RelationFact.create(
                                        child,
                                        RelationEndpoint.resolve(
                                                fixture.authority,
                                                fixture.selector.scope,
                                                CompilerGroundedSymbolEvidence.fromSelector(fixture.selector),
                                            )
                                            .refined(),
                                        child.subject,
                                        RelationOccurrence.fromBoundary(
                                                child.subject.file,
                                                10 + index * 2,
                                                11 + index * 2,
                                            )
                                            .refined(),
                                        RelationProvenance.K2_AUTHORED_SOURCE,
                                    )
                                    .refined()
                            }
                        val records = facts.map {
                            TraversalRecord.create(
                                    plan,
                                    child.subject.fingerprint,
                                    TraversalDepth.parse(1).refined(),
                                    it,
                                )
                                .refined()
                        }
                        val page =
                            TraversalPage.fromBoundary(
                                    plan,
                                    records,
                                    records.sumOf {
                                        it.fact.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong()
                                    },
                                    1,
                                    0,
                                    1,
                                    TraversalProgress.restore(1, 1, 0, 1).refined(),
                                )
                                .refined()
                        TraversalResult.complete(page)
                    },
                traversalCeiling =
                    TraversalBudget(
                        budget.resources.resultLimit,
                        TraversalByteLimit.parse(100_000).refined(),
                        budget.resources.workUnitLimit,
                        budget.resources.elapsedTimeLimit,
                        TraversalDepthLimit.parse(1).refined(),
                        TraversalFrontierLimit.parse(100).refined(),
                        fixture.budget,
                    ),
                clock = QueryNanoClock { 0L },
            )
        private val input =
            when (output) {
                QueryOutputDocument.BindingRows -> bindingRequest(state)
                QueryOutputDocument.Occurrences ->
                    request.copy(
                        output = output,
                        steps = bounded(listOf(QueryStepDocument.Related(RelationKindDocument.REFERENCES))),
                    )
                QueryOutputDocument.TraversalRecords ->
                    request.copy(
                        output = output,
                        steps =
                            bounded(
                                listOf(
                                    QueryStepDocument.Walk(
                                        RelationKindDocument.REFERENCES,
                                        ProtocolCount.parse(1).refined(),
                                    )
                                )
                            ),
                    )
                is QueryOutputDocument.Symbols,
                QueryOutputDocument.ValuePaths,
                is QueryOutputDocument.ImpactWitness -> error("Invalid case")
            }
        private val protocol = CanonicalQueryProtocol(service, fixture.references, state)
        private val grant =
            budget.copy(resources = budget.resources.copy(resultLimit = ResultLimit.parse(limit).refined()))

        suspend fun drain(): List<QueryResultItemDocument> {
            val result =
                protocol.executeAutomatically(input, fixture.authority, grant, policy(1, retainedBytes = 128_000_000))
            assertInstanceOf(OperationOutcome.Complete::class.java, result, "$output: $result")
            val payload = (result as OperationOutcome.Complete).evidence.payload
            val beforeReads = nativeCalls
            val items = retainedItems(payload)
            assertEquals(beforeReads, nativeCalls, "Retained reads executed semantic providers")
            return items.map { item ->
                when (item) {
                    is QueryResultItemDocument.BindingRow ->
                        QueryResultItemDocument.BindingRow.create(item.left, item.right).refined()
                    is QueryResultItemDocument.Occurrence -> item.copy(rowId = null)
                    is QueryResultItemDocument.TraversalRecord -> item.copy(rowId = null)
                    is QueryResultItemDocument.ExactSymbol,
                    is QueryResultItemDocument.ReferenceOccurrence,
                    is QueryResultItemDocument.ValuePath,
                    is QueryResultItemDocument.ImpactWitness -> error("Unexpected projected row")
                }
            }
        }

        private suspend fun retainedItems(payload: QueryRunResult): List<QueryResultItemDocument> {
            val items = payload.items.values.toMutableList()
            var cursor = payload.nextCursor
            val reference = (payload.retention as? QueryResultRetention.Retained)?.reference
            while (cursor != null) {
                val read = readRequest(requireNotNull(reference), cursor)
                val tail = protocol.execute(read, fixture.authority, grant) as OperationOutcome.Complete
                items += tail.evidence.payload.items.values
                cursor = tail.evidence.payload.nextCursor
            }
            return items
        }

        private fun readRequest(
            reference: QueryResultReference,
            cursor: QueryResultCursor,
        ): QueryRunRequest.ReadResult =
            when (output) {
                QueryOutputDocument.BindingRows -> QueryRunRequest.ReadResult.bindingRows(reference, cursor)
                QueryOutputDocument.Occurrences -> QueryRunRequest.ReadResult.occurrences(reference, cursor)
                QueryOutputDocument.TraversalRecords -> QueryRunRequest.ReadResult.traversalRecords(reference, cursor)
                is QueryOutputDocument.Symbols,
                QueryOutputDocument.ValuePaths,
                is QueryOutputDocument.ImpactWitness -> error("Invalid case")
            }
    }

    private fun bindingRequest(state: QueryStateStore): QueryRunRequest.Run {
        val complete =
            QueryExecutionResult.Complete.create(
                QueryResult(QueryRows.Symbols.of(listOf(row, row, row)), emptyList()),
                QueryCoverage.Complete(QueryCount.parse(3).refined()),
            )
        val issued =
            state.issueResult(request, QueryRetainedResult.capture(fixture.authority, complete).refined())
                as QueryResultIssuance.Issued
        return request.copy(
            output = QueryOutputDocument.BindingRows,
            steps =
                bounded(
                    listOf(
                        QueryStepDocument.Join(
                            QueryJoinModeDocument.Inner(
                                QueryBindingNameDocument.parse("origin").refined(),
                                QueryBindingNameDocument.parse("target").refined(),
                            ),
                            QueryFromDocument.Result(issued.reference),
                        )
                    )
                ),
        )
    }
}
