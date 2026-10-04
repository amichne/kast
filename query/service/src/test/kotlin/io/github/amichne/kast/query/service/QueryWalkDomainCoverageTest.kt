package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.query.contract.QuerySymbolFields
import io.github.amichne.kast.query.contract.QueryWalkCoverage
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationScopeFingerprint
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.traversal.contract.TraversalPage
import io.github.amichne.kast.traversal.contract.TraversalProgress
import io.github.amichne.kast.traversal.contract.TraversalResult
import io.github.amichne.kast.traversal.contract.TraversalStrategy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class QueryWalkDomainCoverageTest {
    @Test
    fun `empty exhausted walk retains actual effective domain through semantic free composition`() = runTest {
        val fixture = QueryServiceTest()
        val seed = fixture.selector(fixture.selection())
        val domain = testOnlyDomain()
        val expansion = RelationSearchBoundary.Explicit(domain)
        val plan =
            fixture.exactReferencePlan(
                listOf(seed),
                listOf(
                    QueryStepSyntax.Walk(
                        RelationMeaning.References,
                        TraversalDepthLimit.parse(2).refined(),
                        TraversalStrategy.BreadthFirst,
                        expansion = expansion,
                    )
                ),
            )
        val request = fixture.request(plan, 100L)
        val service = service(fixture, emptyExhaustedTraversal())
        val complete = assertInstanceOf(QueryExecutionResult.Complete::class.java, service.run(request))
        assertEquals(0, complete.result.symbolRows().size)
        val observed = complete.result.walkObservations.single()
        assertEquals(QueryWalkCoverage.Complete, observed.coverage)
        assertEquals(expansion, observed.question.requestedDomain)
        assertEquals(domain, observed.question.effectiveScope)
        assertEquals(SymbolDiscoveryConstraints.None, observed.question.effectiveConstraints)
        assertEquals(
            RelationScopeFingerprint.from(RelationEndpoint.subject(seed), expansion),
            observed.question.domainFingerprint,
        )
        assertEquals(seed.lease, observed.question.subject.lease)
        val retained = QueryRetainedResult.capture(request.lease, complete).refined()
        val suffix =
            fixture.admittedPlan(
                QuerySourceSyntax.Retained(retained),
                emptyList(),
                QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refined()),
            )
        val replay =
            assertInstanceOf(
                QueryExecutionResult.Complete::class.java,
                service(fixture, TraversalOperations { error("Retained walk coverage must not repeat expansion") })
                    .run(fixture.request(suffix, 100L)),
            )
        assertEquals(listOf(observed), replay.result.walkObservations)
    }

    private fun testOnlyDomain() =
        SymbolSearchScope.Workspace(
            SymbolSourceKindPolicy.TEST_ONLY,
            SymbolGeneratedSourcePolicy.EXCLUDE,
            SymbolLibraryPolicy.EXCLUDE,
        )

    private fun emptyExhaustedTraversal() = TraversalOperations { actual ->
        val page =
            TraversalPage.fromBoundary(
                    actual,
                    emptyList(),
                    0L,
                    1L,
                    0L,
                    1,
                    TraversalProgress.restore(1L, 1L, 0L, 0).refined(),
                )
                .refined()
        TraversalResult.complete(page)
    }

    private fun service(fixture: QueryServiceTest, traversal: TraversalOperations) =
        QueryService(
            fixture.discoveryEmpty(false),
            fixture.exactOperations(
                describe = { SymbolDescriptionResult.Described(SymbolDescription.from(it)) },
                resolve = { error("No discovery expected") },
            ),
            SourceReadOperations { error("No source expected") },
            RelationOperations { error("No direct relation expected") },
            traversal,
            queryTestTraversalCeiling(),
            clock = QueryNanoClock { 0L },
        )

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Invalid fixture: $failure")
        }
}
