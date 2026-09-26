package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.query.contract.QueryArrivalEvidence
import io.github.amichne.kast.query.contract.QueryBindingName
import io.github.amichne.kast.query.contract.QueryBindingRow
import io.github.amichne.kast.query.contract.QueryBindingValue
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExactReferences
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryJoinMode
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryPredicate
import io.github.amichne.kast.query.contract.QueryPrimitiveField
import io.github.amichne.kast.query.contract.QueryPrimitiveOperator
import io.github.amichne.kast.query.contract.QueryPrimitiveValue
import io.github.amichne.kast.query.contract.QueryRelationOmission
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QuerySymbolFields
import io.github.amichne.kast.query.contract.QueryTerminalReason
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOmissionEvidence
import io.github.amichne.kast.relation.contract.RelationOmissionMeasurement
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationProviderKind
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.traversal.contract.TraversalPage
import io.github.amichne.kast.traversal.contract.TraversalProgress
import io.github.amichne.kast.traversal.contract.TraversalResult
import io.github.amichne.kast.traversal.contract.TraversalStrategy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryJoinCompositionTest {
    @Test
    fun `retained binding selection projects its exact right cell into later symbol stages`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            val right = retained(selected, listOf(row(selected, 20), row(selected, 21)))
            val mode = QueryJoinMode.Inner.create(name("caller"), name("target")).refined()
            val service = countingService { }
            val joined = admittedPlan(
                QuerySourceSyntax.ExactReferences(QueryExactReferences.from(listOf(selected)).refined()),
                listOf(QueryStepSyntax.Join(mode, right)),
                QueryOutputSyntax.BindingRows,
            )
            val produced = assertInstanceOf(QueryExecutionResult.Complete::class.java, service.run(request(joined, 64L)))
            val bindings = QueryRetainedResult.capture(selected.lease, produced).refined() as QueryRetainedResult.Bindings
            val selectedRows = bindings.selectRows(listOf(1)).refined()
            val projected = admittedPlan(
                QuerySourceSyntax.Retained(selectedRows),
                listOf(QueryStepSyntax.ProjectBinding(name("target")), QueryStepSyntax.Distinct),
                QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refined()),
            )
            val result = assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(request(projected, 64L)))
            assertEquals(1, result.result.symbolRows().size)
            assertEquals(21, result.result.symbolRows().single().connections.single().occurrence.range.startInclusive)
            assertEquals(listOf(QueryLimitation.ROW_SELECTION_INCOMPLETE), result.coverage.limitations)
        }
    }

    @Test
    fun `inner join projects a named cell before distinct in one request`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            val right = retained(selected, listOf(row(selected, 20), row(selected, 21)))
            val mode = QueryJoinMode.Inner.create(name("caller"), name("target")).refined()
            val plan = admittedPlan(
                QuerySourceSyntax.ExactReferences(QueryExactReferences.from(listOf(selected)).refined()),
                listOf(
                    QueryStepSyntax.Join(mode, right),
                    QueryStepSyntax.ProjectBinding(name("target")),
                    QueryStepSyntax.Distinct,
                ),
                QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refined()),
            )
            var descriptions = 0
            val service = countingService { descriptions++ }
            val result = assertInstanceOf(QueryExecutionResult.Complete::class.java, service.run(request(plan, 64L)))
            assertEquals(1, result.result.symbolRows().size)
            assertEquals(20, result.result.symbolRows().single().connections.single().occurrence.range.startInclusive)
            descriptions = 0
            val firstRequest = request(plan, workLimit = 2L)
            val observed = mutableListOf<QuerySymbol>()
            var page: QueryExecutionResult = service.run(firstRequest)
            var pages = 0
            while (page is QueryExecutionResult.Qualified) {
                observed += page.result.symbolRows()
                val continuation = assertInstanceOf(QueryContinuationState.Resumable::class.java, page.continuation)
                page = service.run(
                    QueryExecutionRequest.create(plan, firstRequest.lease, firstRequest.budget, continuation.checkpoint)
                        .refined()
                )
                pages++
                assertTrue(pages < 16)
            }
            observed += assertInstanceOf(QueryExecutionResult.Complete::class.java, page).result.symbolRows()
            assertEquals(result.result.symbolRows(), observed)
            assertEquals(1, descriptions)
        }
    }

    @Test
    fun `selected retained binding projects through distinct into a depth two walk`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            val right = retained(selected, listOf(row(selected, 20), row(selected, 21)))
            val mode = QueryJoinMode.Inner.create(name("caller"), name("target")).refined()
            val joined = admittedPlan(
                QuerySourceSyntax.ExactReferences(QueryExactReferences.from(listOf(selected)).refined()),
                listOf(QueryStepSyntax.Join(mode, right)),
                QueryOutputSyntax.BindingRows,
            )
            val produced = assertInstanceOf(
                QueryExecutionResult.Complete::class.java,
                countingService { }.run(request(joined, 64L)),
            )
            val retained = QueryRetainedResult.capture(selected.lease, produced).refined() as QueryRetainedResult.Bindings
            val chosen = retained.selectRows(listOf(1)).refined()
            var walks = 0
            val walking = QueryService(
                discoveryEmpty(false),
                exactOperations(describe = { error("Retained row must not be reacquired") }, resolve = { error("No discovery") }),
                SourceReadOperations { error("No source read") },
                io.github.amichne.kast.relation.contract.RelationOperations { error("No relation read") },
                TraversalOperations { plan ->
                    walks++
                    assertEquals(selected, plan.start)
                    assertEquals(2, plan.budget.depth.value)
                    val progress = TraversalProgress.restore(1, 1, 0, 0).refined()
                    TraversalResult.complete(TraversalPage.fromBoundary(plan, emptyList(), 0, 1, 1, 1, progress).refined())
                },
                queryTestTraversalCeiling(),
                clock = QueryNanoClock { 0L },
            )
            val plan = admittedPlan(
                QuerySourceSyntax.Retained(chosen),
                listOf(
                    QueryStepSyntax.ProjectBinding(name("target")),
                    QueryStepSyntax.Distinct,
                    QueryStepSyntax.Walk(
                        RelationMeaning.Callers,
                        TraversalDepthLimit.parse(2).refined(),
                        TraversalStrategy.BreadthFirst,
                    ),
                ),
                QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refined()),
            )
            val result = assertInstanceOf(QueryExecutionResult.Qualified::class.java, walking.run(request(plan, 64L)))
            assertEquals(1, walks)
            assertEquals(listOf(QueryLimitation.ROW_SELECTION_INCOMPLETE), result.coverage.limitations)
            assertEquals(selected, result.result.walkObservations.single().subject)
        }
    }

    @Test
    fun `inner join keeps repeated matching pairs and each right occurrence across result pages`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            val right = retained(selected, listOf(row(selected, 20), row(selected, 21)))
            val mode = QueryJoinMode.Inner.create(name("left"), name("right")).refined()
            val plan =
                admittedPlan(
                    QuerySourceSyntax.ExactReferences(QueryExactReferences.from(listOf(selected, selected)).refined()),
                    listOf(QueryStepSyntax.Join(mode, right)),
                    QueryOutputSyntax.BindingRows,
                )
            var descriptions = 0
            val service = countingService { descriptions++ }
            val unsplit = assertInstanceOf(QueryExecutionResult.Complete::class.java, service.run(request(plan, 64L)))
            val expected = unsplit.result.bindingRows()
            assertEquals(4, expected.size)
            assertEquals(4, unsplit.coverage.resultCount.value)
            assertEquals(listOf(20, 21, 20, 21), expected.map { it.rightOccurrenceStart() })
            assertEquals(2, descriptions)

            descriptions = 0
            val pageRequest = request(plan, 64L, resultLimit = 2)
            val first = assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(pageRequest))
            assertEquals(2, first.coverage.knownMinimum.value)
            val checkpoint = assertInstanceOf(QueryContinuationState.Resumable::class.java, first.continuation)
            val stored = assertInstanceOf(PipelineCheckpoint::class.java, checkpoint.checkpoint)
            assertEquals(2, stored.joinState.indexes.values.single().consumed)
            assertThrows(UnsupportedOperationException::class.java) {
                (stored.joinState.indexes.values.single().positions as MutableMap).clear()
            }
            assertThrows(UnsupportedOperationException::class.java) {
                (stored.joinState.indexes.values.single().positions.values.single() as MutableList).clear()
            }
            val second =
                service.run(QueryExecutionRequest.create(plan, pageRequest.lease, pageRequest.budget, stored).refined())
            assertEquals(expected, first.result.bindingRows() + second.bindingRows())
            assertEquals(2, descriptions)
        }
    }

    @Test
    fun `semi join retains left multiplicity and merges every matching right occurrence`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            val left = retained(selected, listOf(row(selected, 10), row(selected, 11)))
            val right = retained(selected, listOf(row(selected, 20), row(selected, 21)))
            val plan =
                admittedPlan(
                    QuerySourceSyntax.Retained(left),
                    listOf(QueryStepSyntax.Join(QueryJoinMode.Semi, right)),
                    QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refined()),
                )
            val result = assertInstanceOf(QueryExecutionResult.Complete::class.java, service().run(request(plan, 64L)))
            assertEquals(2, result.result.symbolRows().size)
            assertEquals(
                listOf(listOf(10, 20, 21), listOf(11, 20, 21)),
                result.result.symbolRows().map { symbol ->
                    symbol.connections.map { it.occurrence.range.startInclusive }
                },
            )
        }
    }

    @Test
    fun `join resumes mid build without repeating the completed exact prefix`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            val right = retained(selected, listOf(row(selected, 20), row(selected, 21), row(selected, 22)))
            val mode = QueryJoinMode.Inner.create(name("left"), name("right")).refined()
            val plan =
                admittedPlan(
                    QuerySourceSyntax.ExactReferences(QueryExactReferences.from(listOf(selected)).refined()),
                    listOf(QueryStepSyntax.Join(mode, right)),
                    QueryOutputSyntax.BindingRows,
                )
            var descriptions = 0
            val service = countingService { descriptions++ }
            val firstRequest = request(plan, workLimit = 2L)
            var result = service.run(firstRequest)
            val observed = mutableListOf<QueryBindingRow>()
            var pages = 0
            while (result is QueryExecutionResult.Qualified) {
                observed += result.result.bindingRows()
                val progress = assertInstanceOf(QueryContinuationState.Resumable::class.java, result.continuation)
                if (pages == 0) {
                    val checkpoint = assertInstanceOf(PipelineCheckpoint::class.java, progress.checkpoint)
                    assertEquals(1, checkpoint.joinState.indexes.values.single().consumed)
                }
                result =
                    service.run(
                        QueryExecutionRequest.create(plan, firstRequest.lease, firstRequest.budget, progress.checkpoint)
                            .refined()
                    )
                pages++
                assertTrue(pages < 8)
            }
            val complete = assertInstanceOf(QueryExecutionResult.Complete::class.java, result)
            observed += complete.result.bindingRows()
            assertEquals(listOf(20, 21, 22), observed.map(QueryBindingRow::rightOccurrenceStart))
            assertEquals(1, descriptions)
            assertTrue(pages >= 2)
        }
    }
}

class QueryJoinBoundaryTest {
    @Test
    fun `empty left join still returns qualified right omission evidence`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            val left = retained(selected, emptyList())
            val omission =
                QueryRelationOmission.create(
                        selected,
                        RelationMeaning.Callees,
                        RelationOmissionEvidence.fromObservedPage(
                            RelationProviderKind.forMeaning(RelationMeaning.Callees),
                            RelationLimitation.UNSUPPORTED_ITEM,
                            RelationOmissionMeasurement.UnmeasuredOnPage,
                            emptyList(),
                        ),
                    )
                    .refined()
            val right =
                retained(
                    selected,
                    emptyList(),
                    QueryCoverage.Qualified.create(
                            QueryCount.parse(0).refined(),
                            setOf(QueryLimitation.RELATION_INCOMPLETE),
                        )
                        .refined(),
                    omissions = listOf(omission),
                )
            val mode = QueryJoinMode.Inner.create(name("left"), name("right")).refined()
            val plan =
                admittedPlan(
                    QuerySourceSyntax.Retained(left),
                    listOf(QueryStepSyntax.Join(mode, right)),
                    QueryOutputSyntax.BindingRows,
                )
            val result = assertInstanceOf(QueryExecutionResult.Qualified::class.java, service().run(request(plan, 8L)))
            assertTrue(result.result.bindingRows().isEmpty())
            assertEquals(listOf(omission), result.result.omissions)
            assertTrue(QueryLimitation.RELATION_INCOMPLETE in result.coverage.limitations)
        }
    }


}

private fun QueryServiceTest.countingService(onDescription: () -> Unit): QueryService =
    service(
        exact =
            exactOperations(
                describe = {
                    onDescription()
                    SymbolDescriptionResult.Described(SymbolDescription.from(it))
                },
                resolve = { error("No discovery expected") },
            )
    )

private fun name(raw: String): QueryBindingName = QueryBindingName.parse(raw).refined()

private fun retained(
    owner: SymbolSelector,
    rows: List<QuerySymbol>,
    coverage: QueryCoverage = QueryCoverage.Complete(QueryCount.parse(rows.size).refined()),
    failures: List<QueryItemFailure> = emptyList(),
    omissions: List<QueryRelationOmission> = emptyList(),
): QueryRetainedResult.Symbols {
    val result = QueryResult(QueryRows.Symbols.of(rows), failures, omissions)
    val execution =
        when (coverage) {
            is QueryCoverage.Complete -> QueryExecutionResult.Complete(result, coverage)
            is QueryCoverage.Qualified -> QueryExecutionResult.Qualified(result, coverage)
        }
    return assertInstanceOf(
        QueryRetainedResult.Symbols::class.java,
        QueryRetainedResult.capture(owner.lease, execution).refined(),
    )
}

private fun row(selector: SymbolSelector, start: Int): QuerySymbol {
    val fact = relationFact(selector, start)
    return QuerySymbol(
        SymbolDescription.from(selector),
        listOf(fact),
        arrival = QueryArrivalEvidence.Proven.one(fact),
    )
}

private fun anotherDeclaration(source: SymbolSelector): SymbolSelector =
    SymbolSelector.issue(
        source.lease,
        source.scope,
        CompilerGroundedSymbolEvidence.fromBoundary(
                source.file,
                source.range.startInclusive,
                source.range.endExclusive + 1,
                source.name.value,
                "sample.PaymentService",
                source.kind,
                source.signature,
            )
            .refined(),
    )

private fun relationFact(source: SymbolSelector, start: Int): RelationFact {
    val read =
        RelationRequest.start(
            source,
            RelationMeaning.Callees,
            RelationBudget(
                ResourceBudget(
                    ResultLimit.parse(8).refined(),
                    WorkUnitLimit.parse(32L).refined(),
                    ElapsedTimeLimitMillis.parse(1_000L).refined(),
                ),
                RelationByteLimit.parse(100_000L).refined(),
            ),
        )
    val target =
        RelationEndpoint.resolve(source.lease, source.scope, CompilerGroundedSymbolEvidence.fromSelector(source))
            .refined()
    return RelationFact.create(
            read,
            read.subject,
            target,
            RelationOccurrence.fromBoundary(source.file, start, start + 1).refined(),
            RelationProvenance.K2_AUTHORED_SOURCE,
        )
        .refined()
}

private fun QueryResult.bindingRows(): List<QueryBindingRow> = (rows as QueryRows.Bindings).values

private fun QueryExecutionResult.bindingRows(): List<QueryBindingRow> =
    when (this) {
        is QueryExecutionResult.Complete -> result.bindingRows()
        is QueryExecutionResult.Qualified -> result.bindingRows()
        is QueryExecutionResult.Rejected -> error("Unexpected rejection: $reason")
    }

private fun QueryBindingRow.rightOccurrenceStart(): Int =
    (right.value as QueryBindingValue.Occurrence).fact.occurrence.range.startInclusive

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = (this as Refinement.Refined).value
