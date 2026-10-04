package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionRejection
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryRelationCoverage
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.query.contract.QuerySymbolFields
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationScopeExclusion
import io.github.amichne.kast.relation.contract.RelationScopeExclusionReason
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class QueryRelationCoverageTest {
    @Test
    fun `empty exhausted relation retains exact question domain through semantic free composition`() = runTest {
        val fixture = QueryServiceTest()
        val seed = fixture.selector(fixture.selection())
        val domain =
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.TEST_ONLY,
                SymbolGeneratedSourcePolicy.EXCLUDE,
                SymbolLibraryPolicy.EXCLUDE,
            )
        val boundary = RelationSearchBoundary.Explicit(domain)
        val plan =
            fixture.exactReferencePlan(
                listOf(seed),
                listOf(QueryStepSyntax.Related(RelationMeaning.References, boundary)),
            )
        val request = fixture.request(plan, 100L)
        val service =
            fixture.coverageService(
                relations =
                    RelationOperations { child ->
                        val complete = RelationCompilation.complete(emptyBatch(child))
                        RelationReadResult.Complete(complete.batch, complete.coverage)
                    }
            )
        val complete = assertInstanceOf(QueryExecutionResult.Complete::class.java, service.run(request))
        assertEquals(0, complete.result.symbolRows().size)
        val observed = complete.result.relationObservations.single()
        assertEquals(seed.fingerprint.value, observed.question.subject.fingerprint.value)
        assertEquals(RelationMeaning.References, observed.question.meaning)
        assertEquals(request.lease, observed.question.subject.lease)
        assertEquals(boundary, observed.question.requestedDomain)
        assertEquals(domain, observed.question.effectiveScope)
        assertEquals(SymbolDiscoveryConstraints.None, observed.question.effectiveConstraints)
        assertEquals(QueryRelationCoverage.Exhausted, observed.coverage)

        val retained = QueryRetainedResult.capture(request.lease, complete).refined()
        val suffix = retainedSymbols(fixture, retained)
        val replay =
            assertInstanceOf(
                QueryExecutionResult.Complete::class.java,
                fixture
                    .coverageService(
                        relations = RelationOperations { error("Retained evidence cannot repeat relation search") }
                    )
                    .run(fixture.request(suffix, 100L)),
            )
        assertEquals(listOf(observed), replay.result.relationObservations)
    }

    @Test
    fun `every terminal relation limitation prevents complete absence and retains its domain`() = runTest {
        val fixture = QueryServiceTest()
        val seed = fixture.selector(fixture.selection())
        val plan = fixture.exactReferencePlan(listOf(seed), listOf(QueryStepSyntax.Related(RelationMeaning.References)))
        val request = fixture.request(plan, 100L)
        for (limitation in RelationLimitation.entries) {
            val service =
                fixture.coverageService(
                    relations =
                        RelationOperations { child ->
                            val qualified =
                                RelationCompilation.qualifiedTerminal(emptyBatch(child), setOf(limitation)).refined()
                            RelationReadResult.Qualified(qualified.batch, qualified.coverage)
                        }
                )
            val qualified = assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(request))
            val observed = qualified.result.relationObservations.single()
            val coverage = assertInstanceOf(QueryRelationCoverage.TerminalIncomplete::class.java, observed.coverage)
            assertEquals(setOf(limitation), coverage.limitations)
            assertEquals(seed.fingerprint.value, observed.question.subject.fingerprint.value)
            assertEquals(
                QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION),
                QueryExecutionResult.Complete.create(
                    qualified.result,
                    QueryCoverage.Complete(QueryCount.parse(0).refined()),
                ),
                "terminal limit $limitation is not absence",
            )
        }
    }

    @Test
    fun `proven domain exits survive evidence units and retained composition without making absence incomplete`() =
        runTest {
            // Native membership and compiler target are starting facts; this proves query propagation only.
            val fixture = QueryServiceTest()
            val seed = fixture.selector(fixture.selection())
            val plan =
                fixture.exactReferencePlan(listOf(seed), listOf(QueryStepSyntax.Related(RelationMeaning.Callees)))
            val request = fixture.request(plan, 100L)
            var exits: List<RelationScopeExclusion> = emptyList()
            val result =
                assertInstanceOf(
                    QueryExecutionResult.Complete::class.java,
                    fixture
                        .coverageService(
                            RelationOperations { child ->
                                val batch = scopeExclusionBatch(child, seed)
                                exits = batch.scopeExclusions
                                val compiled = RelationCompilation.complete(batch)
                                RelationReadResult.Complete(compiled.batch, compiled.coverage)
                            }
                        )
                        .run(request),
                )
            assertEquals(0, result.result.symbolRows().size)
            assertEquals(exits, result.result.relationObservations.flatMap { it.scopeExclusions })
            assertEquals(
                listOf(QueryRelationCoverage.Exhausted, QueryRelationCoverage.Exhausted),
                result.result.relationObservations.map { it.coverage },
            )
            val retained = QueryRetainedResult.capture(request.lease, result).refined()
            assertEquals(exits, retained.relationObservations.flatMap { it.scopeExclusions })
            val suffix =
                fixture.admittedPlan(
                    QuerySourceSyntax.Retained(retained),
                    emptyList(),
                    QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refined()),
                )
            val replay =
                assertInstanceOf(
                    QueryExecutionResult.Complete::class.java,
                    fixture
                        .coverageService(
                            RelationOperations { error("Retained exits cannot repeat native enumeration") }
                        )
                        .run(fixture.request(suffix, 100L)),
                )
            assertEquals(exits, replay.result.relationObservations.flatMap { it.scopeExclusions })
        }

    private fun retainedSymbols(fixture: QueryServiceTest, retained: QueryRetainedResult) =
        fixture.admittedPlan(
            QuerySourceSyntax.Retained(retained),
            emptyList(),
            QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refined()),
        )

    private fun QueryServiceTest.coverageService(relations: RelationOperations): QueryService =
        QueryService(
            discoveryEmpty(false),
            exactOperations(
                describe = { SymbolDescriptionResult.Described(SymbolDescription.from(it)) },
                resolve = { error("No discovery expected") },
            ),
            io.github.amichne.kast.source.contract.SourceReadOperations { error("No source expected") },
            relations,
            unexpectedQueryTraversal(),
            queryTestTraversalCeiling(),
            clock = QueryNanoClock { 0L },
        )

    private fun scopeExclusionBatch(
        child: RelationRequest,
        seed: io.github.amichne.kast.symbol.contract.SymbolSelector,
    ): RelationBatch {
        val target = io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence.fromSelector(seed)
        val exits =
            listOf(9, 11)
                .map { offset ->
                    RelationScopeExclusion.fromNativeBoundary(
                            child,
                            RelationOccurrence.fromBoundary(seed.file, offset, offset + 1).refined(),
                            target,
                            RelationScopeExclusionReason.LIBRARY_POLICY,
                        )
                        .refined()
                }
                .sorted()
        val bytes = exits.sumOf { it.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong() }
        return RelationBatch.create(
                child,
                emptyList(),
                RelationByteCount.parse(bytes).refined(),
                RelationWorkCount.parse(2L).refined(),
                RelationResultCount.parse(0).refined(),
                scopeExclusions = exits,
            )
            .refined()
    }

    private fun emptyBatch(request: RelationRequest): RelationBatch =
        RelationBatch.create(
                request,
                emptyList(),
                RelationByteCount.parse(0L).refined(),
                RelationWorkCount.parse(0L).refined(),
                RelationResultCount.parse(0).refined(),
            )
            .refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Invalid fixture: $failure")
        }
}
