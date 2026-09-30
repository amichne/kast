package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExactReferences
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryOccurrence
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.query.contract.QueryWalkObservation
import io.github.amichne.kast.relation.contract.RelationConfirmedReferenceTarget
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationReferenceContext
import io.github.amichne.kast.relation.contract.RelationReferenceOccurrence
import io.github.amichne.kast.relation.contract.RelationReferenceOwnership
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.traversal.contract.TraversalDepth
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierEntry
import io.github.amichne.kast.traversal.contract.TraversalNode
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.traversal.contract.TraversalPage
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalProgress
import io.github.amichne.kast.traversal.contract.TraversalReferenceObservation
import io.github.amichne.kast.traversal.contract.TraversalResult
import io.github.amichne.kast.traversal.contract.TraversalStrategy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/** Pure scheduling proof; installed fixtures establish native target and occurrence authority. */
class QueryWalkEvidencePaginationTest {
    @Test
    fun `bounded walk evidence pages drain without repeated expansion`() = runTest {
        val fixture = WalkEvidenceFixture()
        val initial = fixture.scope.request(fixture.plan, 100, resultLimit = 9, returnedBytes = 1_000_000L)
        val first = assertInstanceOf(QueryExecutionResult.Qualified::class.java, fixture.service.run(initial))
        assertEquals(
            fixture.expectedOffsets,
            (first.result.rows as QueryRows.Occurrences).values.map {
                (it as QueryOccurrence.Reference).value.occurrence.range.startInclusive
            },
        )
        val original = fixture.observation
        val grant = original.evidenceUnits().maxOf { it.projectedUtf8Size() }
        assertTrue(original.projectedUtf8Size() > grant)
        val observations = fixture.drain(first, grant)
        assertEquals(1, fixture.expansions)
        assertEquals(
            fixture.expectedOffsets,
            observations.flatMap { it.referenceOccurrences }.map { it.reference.occurrence.range.startInclusive },
        )
        assertTrue(
            observations.all {
                it.progress == original.progress && it.coverage == original.coverage && it.subject == original.subject
            }
        )
    }
}

private class WalkEvidenceFixture {
    val scope = QueryServiceTest()
    val selected = scope.selector(scope.selection())
    val expectedOffsets = (1..9).map { it * 10 }
    val plan: AdmittedQueryPlan =
        scope.admittedPlan(
            QuerySourceSyntax.ExactReferences(QueryExactReferences.from(listOf(selected)).refinedWalk()),
            listOf(
                QueryStepSyntax.Walk(
                    RelationMeaning.References,
                    TraversalDepthLimit.parse(1).refinedWalk(),
                    TraversalStrategy.BreadthFirst,
                )
            ),
            QueryOutputSyntax.Occurrences,
        )
    var expansions = 0
        private set

    lateinit var observation: QueryWalkObservation
        private set

    val service =
        QueryService(
            scope.discoveryEmpty(false),
            scope.exactOperations(
                describe = { SymbolDescriptionResult.Described(SymbolDescription.from(it)) },
                resolve = { error("No discovery expected") },
            ),
            SourceReadOperations { error("No source effect expected") },
            RelationOperations { error("Expansion belongs to traversal") },
            TraversalOperations(::expand),
            queryTestTraversalCeiling(),
            clock = QueryNanoClock { 0L },
        )

    private fun references(plan: TraversalPlan): List<TraversalReferenceObservation> {
        val child =
            RelationRequest.start(
                selected,
                RelationMeaning.References,
                plan.budget.oneHop,
                RelationSearchBoundary.WORKSPACE_EXPANSION,
            )
        val target =
            RelationConfirmedReferenceTarget.fromCompiler(child.subject, child.subject.compilerIdentity).refinedWalk()
        val entry =
            TraversalFrontierEntry.create(plan, TraversalNode.start(selected), TraversalDepth.parse(0).refinedWalk())
                .refinedWalk()
        return expectedOffsets.map { offset ->
            val occurrence =
                RelationReferenceOccurrence.confirmed(
                        child,
                        target,
                        RelationOccurrence.fromBoundary(selected.file, offset, offset + 1).refinedWalk(),
                        RelationReferenceContext.IMPORT,
                        RelationReferenceOwnership.FileScoped(RelationReferenceContext.IMPORT),
                        RelationProvenance.K2_AUTHORED_SOURCE,
                    )
                    .refinedWalk()
            TraversalReferenceObservation.create(plan, entry, occurrence).refinedWalk()
        }
    }

    private fun expand(plan: TraversalPlan): TraversalResult {
        expansions++
        val references = references(plan)
        val bytes = references.sumOf { it.reference.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong() }
        val page =
            TraversalPage.fromBoundary(
                    plan,
                    emptyList(),
                    bytes,
                    1,
                    0,
                    1,
                    TraversalProgress.restore(1, 1, 0, 0).refinedWalk(),
                    referenceOccurrences = references,
                )
                .refinedWalk()
        return assertInstanceOf(TraversalResult.Complete::class.java, TraversalResult.complete(page)).also {
            observation = QueryWalkObservation.from(it)
        }
    }

    suspend fun drain(first: QueryExecutionResult.Qualified, grant: Long): List<QueryWalkObservation> {
        val observations = mutableListOf<QueryWalkObservation>()
        var next = (first.continuation as QueryContinuationState.Resumable).checkpoint
        repeat(12) {
            val request =
                QueryExecutionRequest.create(
                        plan,
                        selected.lease,
                        scope.request(plan, 100, 20, returnedBytes = grant).budget,
                        next,
                    )
                    .refinedWalk()
            when (val page = service.run(request)) {
                is QueryExecutionResult.Complete -> return observations + page.result.walkObservations
                is QueryExecutionResult.Qualified -> {
                    observations += page.result.walkObservations
                    next = assertInstanceOf(QueryContinuationState.Resumable::class.java, page.continuation).checkpoint
                }
                is QueryExecutionResult.Rejected -> fail("Unexpected rejection: ${page.reason}")
            }
        }
        fail<Unit>("Independent walk evidence did not exhaust within twelve pages")
        return observations
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.refinedWalk(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid fixture: $failure")
    }
