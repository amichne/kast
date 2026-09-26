package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryPlanAdmission
import io.github.amichne.kast.query.contract.QueryPlanAdmissionFailure
import io.github.amichne.kast.query.contract.QueryPlanCompiler
import io.github.amichne.kast.query.contract.QueryPlanSyntax
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRetainedResultFailure
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QuerySymbolFields
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.CanonicalSymbolId
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolExactRejection
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QuerySetCompositionTest {
    @Test
    fun `set membership uses canonical identity across scoped exact selectors`() = runTest {
        QueryServiceTest().apply {
            val left = selector(selection())
            val right = differentlyScoped(left)
            assertNotEquals(left, right)
            assertEquals(CanonicalSymbolId.from(left), CanonicalSymbolId.from(right))
            val retained = retained(right)
            val service =
                service(
                    exact =
                        exactOperations(
                            describe = { SymbolDescriptionResult.Described(SymbolDescription.from(it)) },
                            resolve = { error("No discovery expected") },
                        )
                )

            val intersection =
                service.run(
                    request(exactReferencePlan(listOf(left, left), listOf(QueryStepSyntax.Intersect(retained))), 8L)
                )
            val union =
                service.run(
                    request(exactReferencePlan(listOf(left, left), listOf(QueryStepSyntax.Union(retained))), 8L)
                )
            val difference =
                service.run(request(exactReferencePlan(listOf(left), listOf(QueryStepSyntax.Difference(retained))), 8L))

            assertEquals(1, intersection.rows().size)
            assertEquals(left, intersection.rows().single().selector)
            assertEquals(1, union.rows().size)
            assertEquals(left, union.rows().single().selector)
            assertEquals(0, difference.rows().size)
        }
    }

    @Test
    fun `set membership keeps declarations with the same name and different canonical identities separate`() = runTest {
        QueryServiceTest().apply {
            val selection = selection()
            val left = selector(selection)
            val right = anotherDeclaration(selection, left)
            assertEquals(left.name, right.name)
            assertNotEquals(CanonicalSymbolId.from(left), CanonicalSymbolId.from(right))
            val retained = retained(right)
            val service =
                service(
                    exact =
                        exactOperations(
                            describe = { SymbolDescriptionResult.Described(SymbolDescription.from(it)) },
                            resolve = { error("No discovery expected") },
                        )
                )

            val union =
                service.run(request(exactReferencePlan(listOf(left), listOf(QueryStepSyntax.Union(retained))), 8L))
            val intersection =
                service.run(request(exactReferencePlan(listOf(left), listOf(QueryStepSyntax.Intersect(retained))), 8L))
            val difference =
                service.run(request(exactReferencePlan(listOf(left), listOf(QueryStepSyntax.Difference(retained))), 8L))

            assertEquals(listOf(left, right), union.rows().map(QuerySymbol::selector))
            assertTrue(intersection.rows().isEmpty())
            assertEquals(listOf(left), difference.rows().map(QuerySymbol::selector))
        }
    }

    @Test
    fun `incomplete right supplies positive matches but cannot establish difference`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            val right =
                retained(
                    selected,
                    coverage =
                        QueryCoverage.Qualified.create(
                                QueryCount.parse(1).refined(),
                                setOf(QueryLimitation.DISCOVERY_INCOMPLETE),
                            )
                            .refined(),
                    failures = listOf(QueryItemFailure.PredicateUnproven(selected)),
                )
            val intersectionPlan = exactReferencePlan(listOf(selected), listOf(QueryStepSyntax.Intersect(right)))
            val service =
                service(
                    exact =
                        exactOperations(
                            describe = { SymbolDescriptionResult.Described(SymbolDescription.from(it)) },
                            resolve = { error("No discovery expected") },
                        )
                )
            val result =
                assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(request(intersectionPlan, 8L)))
            assertEquals(1, result.result.symbolRows().size)
            assertEquals(
                listOf(QueryLimitation.DISCOVERY_INCOMPLETE, QueryLimitation.VISIBILITY_INCOMPLETE),
                result.coverage.limitations,
            )
            assertEquals(listOf(QueryItemFailure.PredicateUnproven(selected)), result.result.failures)

            val rejected =
                QueryPlanCompiler.admit(
                    QueryPlanSyntax(
                        QuerySourceSyntax.Retained(right),
                        listOf(QueryStepSyntax.Difference(right)),
                        QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refined()),
                    )
                )
            assertEquals(QueryPlanAdmission.Rejected(QueryPlanAdmissionFailure.IncompleteRightInput), rejected)
        }
    }

    @Test
    fun `retention rejects complete coverage paired with failed exact evidence`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            val failure = QueryItemFailure.ExactReference(selected, SymbolExactRejection.AMBIGUOUS_DECLARATION)
            val malformed =
                QueryExecutionResult.Complete(
                    QueryResult(
                        QueryRows.Symbols.of(listOf(QuerySymbol(SymbolDescription.from(selected), emptyList()))),
                        listOf(failure),
                    ),
                    QueryCoverage.Complete(QueryCount.parse(1).refined()),
                )
            val rejected = QueryRetainedResult.capture(selected.lease, malformed)
            assertEquals(QueryRetainedResultFailure.INCONSISTENT_COVERAGE, (rejected as Refinement.Rejected).failure)
        }
    }

    @Test
    fun `row selection preserves original rows and marks unchecked omissions`() {
        QueryServiceTest().apply {
            val first = selector(selection())
            val second = differentlyScoped(first)
            val retained = retained(first, second)
            val selection = retained.selectRows(listOf(1)).refined()
            assertEquals(listOf(second), selection.symbols.map(QuerySymbol::selector))
            val coverage = assertInstanceOf(QueryCoverage.Qualified::class.java, selection.coverage)
            assertEquals(listOf(QueryLimitation.ROW_SELECTION_INCOMPLETE), coverage.limitations)
            assertEquals(1, coverage.knownMinimum.value)
            assertEquals(
                io.github.amichne.kast.query.contract.QueryContinuationState.Terminal(
                    io.github.amichne.kast.query.contract.QueryTerminalReason.UPSTREAM_INCOMPLETE
                ),
                selection.producerProgress,
            )
            assertEquals(0, retained.selectRows(emptyList()).refined().symbols.size)
            assertEquals(
                QueryRetainedResultFailure.UNKNOWN_ROW,
                (retained.selectRows(listOf(2)) as Refinement.Rejected).failure,
            )
            assertEquals(
                QueryRetainedResultFailure.DUPLICATE_ROW,
                (retained.selectRows(listOf(0, 0)) as Refinement.Rejected).failure,
            )
            val difference =
                QueryPlanCompiler.admit(
                    QueryPlanSyntax(
                        QuerySourceSyntax.Retained(retained),
                        listOf(QueryStepSyntax.Difference(selection)),
                        QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refined()),
                    )
                )
            assertEquals(QueryPlanAdmission.Rejected(QueryPlanAdmissionFailure.IncompleteRightInput), difference)
            assertTrue(retained.coverage is QueryCoverage.Complete)
        }
    }

    @Test
    fun `union and intersection merge distinct established relation occurrences`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            val first = relationFact(selected, 100)
            val second = relationFact(selected, 102)
            val left = retainedRows(selected, listOf(QuerySymbol(SymbolDescription.from(selected), listOf(first))))
            val right = retainedRows(selected, listOf(QuerySymbol(SymbolDescription.from(selected), listOf(second))))
            val service = service()
            for (step in listOf(QueryStepSyntax.Union(right), QueryStepSyntax.Intersect(right))) {
                val plan =
                    admittedPlan(
                        QuerySourceSyntax.Retained(left),
                        listOf(step),
                        QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refined()),
                    )
                val result = service.run(request(plan, 8L))
                assertEquals(listOf(first, second).sorted(), result.rows().single().connections)
            }
        }
    }

    @Test
    fun `distinct after relation expansion merges repeated occurrence evidence`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            val service =
                QueryService(
                    discoveryEmpty(false),
                    exactOperations(
                        describe = { SymbolDescriptionResult.Described(SymbolDescription.from(it)) },
                        resolve = { error("No discovery expected") },
                    ),
                    SourceReadOperations { error("No source expected") },
                    repeatedRelationOperations(selected),
                    unexpectedQueryTraversal(),
                    queryTestTraversalCeiling(),
                    clock = QueryNanoClock { 0L },
                )
            val plan =
                exactReferencePlan(
                    listOf(selected),
                    listOf(QueryStepSyntax.Related(RelationMeaning.Callees), QueryStepSyntax.Distinct),
                )
            val rows = service.run(request(plan, 16L)).rows()
            assertEquals(1, rows.size)
            assertEquals(listOf(100, 102), rows.single().connections.map { it.occurrence.range.startInclusive })
        }
    }

    @Test
    fun `set pagination resumes aggregated rows without revalidating the completed prefix`() = runTest {
        QueryServiceTest().apply {
            val selection = selection()
            val first = selector(selection)
            val second = anotherDeclaration(selection, first)
            val right = retained(first, second)
            var descriptions = 0
            val service =
                service(
                    exact =
                        exactOperations(
                            describe = {
                                descriptions++
                                SymbolDescriptionResult.Described(SymbolDescription.from(it))
                            },
                            resolve = { error("No discovery expected") },
                        )
                )
            val plan = exactReferencePlan(listOf(first, second), listOf(QueryStepSyntax.Intersect(right)))
            val firstRequest = request(plan, 8L, resultLimit = 1)
            val firstPage = assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(firstRequest))
            assertEquals(1, firstPage.result.symbolRows().size)
            val checkpoint = assertInstanceOf(QueryContinuationState.Resumable::class.java, firstPage.continuation)
            val secondPage =
                service.run(
                    QueryExecutionRequest.create(plan, firstRequest.lease, firstRequest.budget, checkpoint.checkpoint)
                        .refined()
                )
            assertEquals(1, secondPage.rows().size)
            assertEquals(2, descriptions)
            assertEquals(
                setOf(first, second),
                (firstPage.rows() + secondPage.rows()).map(QuerySymbol::selector).toSet(),
            )
        }
    }
}

private fun differentlyScoped(source: SymbolSelector): SymbolSelector =
    SymbolSelector.issue(
        source.lease,
        SymbolSearchScope.Workspace(
            SymbolSourceKindPolicy.PRODUCTION_ONLY,
            SymbolGeneratedSourcePolicy.EXCLUDE,
            SymbolLibraryPolicy.EXCLUDE,
        ),
        CompilerGroundedSymbolEvidence.fromSelector(source),
    )

private fun anotherDeclaration(
    selection: io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection,
    source: SymbolSelector,
): SymbolSelector =
    SymbolSelector.issue(
            selection,
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
        .refined()

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

private fun repeatedRelationOperations(selected: SymbolSelector): RelationOperations = RelationOperations { read ->
    val facts =
        listOf(100, 102).map { start ->
            RelationFact.create(
                    read,
                    read.subject,
                    read.subject,
                    RelationOccurrence.fromBoundary(selected.file, start, start + 1).refined(),
                    RelationProvenance.K2_AUTHORED_SOURCE,
                )
                .refined()
        }
    val batch =
        RelationBatch.create(
                read,
                facts,
                RelationByteCount.parse(
                        facts.sumOf { fact ->
                            fact.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong()
                        }
                    )
                    .refined(),
                RelationWorkCount.parse(facts.size.toLong()).refined(),
                RelationResultCount.parse(facts.size).refined(),
            )
            .refined()
    RelationReadResult.Complete(batch, RelationCompilation.complete(batch).coverage)
}

private fun retained(
    vararg selectors: SymbolSelector,
    coverage: QueryCoverage = QueryCoverage.Complete(QueryCount.parse(selectors.size).refined()),
    failures: List<QueryItemFailure> = emptyList(),
): QueryRetainedResult.Symbols =
    retainedRows(
        selectors.first(),
        selectors.map { QuerySymbol(SymbolDescription.from(it), emptyList()) },
        coverage,
        failures,
    )

private fun retainedRows(
    owner: SymbolSelector,
    rows: List<QuerySymbol>,
    coverage: QueryCoverage = QueryCoverage.Complete(QueryCount.parse(rows.size).refined()),
    failures: List<QueryItemFailure> = emptyList(),
): QueryRetainedResult.Symbols {
    val result = QueryResult(QueryRows.Symbols.of(rows), failures)
    val execution =
        when (coverage) {
            is QueryCoverage.Complete -> QueryExecutionResult.Complete(result, coverage)
            is QueryCoverage.Qualified -> QueryExecutionResult.Qualified(result, coverage)
        }
    return (QueryRetainedResult.capture(owner.lease, execution).refined() as QueryRetainedResult.Symbols)
}

private fun QueryExecutionResult.rows(): List<QuerySymbol> =
    when (this) {
        is QueryExecutionResult.Complete -> result.symbolRows()
        is QueryExecutionResult.Qualified -> result.symbolRows()
        is QueryExecutionResult.Rejected -> error("Unexpected rejection: $reason")
    }

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = (this as Refinement.Refined).value
