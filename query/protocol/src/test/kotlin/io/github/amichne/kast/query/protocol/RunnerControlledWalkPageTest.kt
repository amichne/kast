package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.QueryExecutionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryWalkObservation
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RunnerControlledRelationBudget
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalCheckpoint
import io.github.amichne.kast.traversal.contract.TraversalContinuation
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalExtent
import io.github.amichne.kast.traversal.contract.TraversalLimitation
import io.github.amichne.kast.traversal.contract.TraversalPage
import io.github.amichne.kast.traversal.contract.TraversalPendingState
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalProgress
import io.github.amichne.kast.traversal.contract.TraversalResult
import io.github.amichne.kast.traversal.contract.TraversalStrategy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/** Production invocation and retention rules consume two explicit semantic page observations. */
internal class RunnerControlledWalkPageTest : AutomaticSymbolQueryCase() {
    @Test
    fun `runner completes after a later page exhausts the same one-hop walk`() = runTest {
        val plan = walkPlan(RelationMeaning.Callers)
        val pages = listOf(resumable(plan), complete(plan))
        val outcome = runPages(pages)
        val completed = assertInstanceOf(OperationOutcome.Complete::class.java, outcome)
        val payload = assertInstanceOf(QueryRunResult::class.java, completed.evidence.payload)
        assertEquals(2, payload.invocation!!.accumulatedRowCount)
    }

    @Test
    fun `terminal one-hop evidence survives a later complete page`() = runTest {
        val plan = walkPlan(RelationMeaning.Callers)
        val terminal =
            QueryWalkObservation.from(
                TraversalResult.qualifiedTerminal(
                        page(plan, TraversalProgress.restore(1, 1, 0, 0).refined()),
                        setOf(TraversalLimitation.ONE_HOP_INCOMPLETE),
                        setOf(RelationLimitation.UNSUPPORTED_ITEM),
                    )
                    .refined()
            )
        assertInstanceOf(OperationOutcome.Rejected::class.java, runPages(listOf(terminal, complete(plan))))
    }

    @Test
    fun `a different walk question cannot discharge a retained one-hop remainder`() = runTest {
        assertInstanceOf(
            OperationOutcome.Rejected::class.java,
            runPages(listOf(resumable(walkPlan(RelationMeaning.Callers)), complete(walkPlan(RelationMeaning.Callees)))),
        )
    }

    @Test
    fun `a different traversal extent strategy or domain cannot discharge a retained one-hop remainder`() = runTest {
        val alternatives =
            listOf(
                walkPlan(extent = TraversalExtent.ThroughDepth(TraversalDepthLimit.parse(1).refined())),
                walkPlan(strategy = TraversalStrategy.BoundedFanOut(ResultLimit.parse(1).refined())),
                walkPlan(expansion = RelationSearchBoundary.WORKSPACE_EXPANSION),
            )
        for (plan in alternatives) {
            assertInstanceOf(
                OperationOutcome.Rejected::class.java,
                runPages(listOf(resumable(walkPlan()), complete(plan))),
            )
        }
    }

    @Test
    fun `completion needs later monotonic progress to discharge a retained one-hop remainder`() = runTest {
        val plan = walkPlan()
        val prior = TraversalProgress.restore(1, 2, 1, 1).refined()
        val alternatives =
            listOf(
                prior,
                TraversalProgress.restore(1, 3, 1, 1).refined(),
                TraversalProgress.restore(2, 2, 1, 1).refined(),
                TraversalProgress.restore(2, 3, 0, 1).refined(),
                TraversalProgress.restore(2, 3, 1, 0).refined(),
            )
        for (progress in alternatives) {
            assertInstanceOf(
                OperationOutcome.Rejected::class.java,
                runPages(listOf(resumable(plan, prior), complete(plan, progress))),
            )
        }
    }

    @Test
    fun `an earlier complete page cannot discharge a later one-hop remainder`() = runTest {
        val plan = walkPlan()
        assertInstanceOf(OperationOutcome.Rejected::class.java, runPages(listOf(complete(plan), resumable(plan))))
    }

    private suspend fun runPages(observations: List<QueryWalkObservation>): QueryPublishedPage {
        val script =
            Script(
                List(2) { listOf(row) },
                resultForPage = { rows, ordinal ->
                    QueryResult(
                        QueryRows.Symbols.of(rows),
                        emptyList(),
                        walkObservations = listOf(observations[ordinal - 1]),
                    )
                },
            )
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val runner =
            request.copy(
                executionMode = QueryExecutionModeDocument.RUNNER_CONTROLLED,
                steps = bounded(listOf(QueryStepDocument.Walk(RelationKindDocument.CALLERS))),
            )
        val grant =
            budget.copy(resources = ResourceBudget.RunnerControlled, pageResults = ResultLimit.parse(1).refined())
        return protocol.execute(runner, fixture.authority, grant, policy(rows = 1)).also { script.assertDrained() }
    }

    private fun walkPlan(
        meaning: RelationMeaning = RelationMeaning.Callers,
        extent: TraversalExtent = TraversalExtent.Exhaustive,
        strategy: TraversalStrategy = TraversalStrategy.BreadthFirst,
        expansion: RelationSearchBoundary = RelationSearchBoundary.RETAINED_SUBJECT,
    ): TraversalPlan =
        TraversalPlan.start(
                fixture.selector,
                meaning,
                TraversalBudget.RunnerControlled(
                    ResultLimit.parse(1).refined(),
                    TraversalByteLimit.parse(100_000).refined(),
                    extent,
                    RunnerControlledRelationBudget.create(fixture.budget.returnedBytes, ResultLimit.parse(1).refined()),
                ),
                strategy,
                expansion,
            )
            .refined()

    private fun resumable(
        plan: TraversalPlan,
        progress: TraversalProgress = TraversalProgress.restore(1, 1, 0, 0).refined(),
    ): QueryWalkObservation {
        val initial = TraversalCheckpoint.initial(plan)
        val checkpoint =
            TraversalCheckpoint.create(
                    plan,
                    initial.frontier,
                    emptySet(),
                    TraversalPendingState.None,
                    progress = progress,
                )
                .refined()
        return QueryWalkObservation.from(
            TraversalResult.qualifiedResumable(
                    page(plan, progress),
                    setOf(TraversalLimitation.ONE_HOP_INCOMPLETE),
                    setOf(RelationLimitation.RESULT_LIMIT_REACHED),
                    TraversalContinuation.issue(plan, checkpoint).refined(),
                )
                .refined()
        )
    }

    private fun complete(
        plan: TraversalPlan,
        progress: TraversalProgress = TraversalProgress.restore(2, 2, 0, 0).refined(),
    ): QueryWalkObservation =
        QueryWalkObservation.from(TraversalResult.complete(page(plan, progress)) as TraversalResult.Complete)

    private fun page(plan: TraversalPlan, progress: TraversalProgress): TraversalPage =
        TraversalPage.fromBoundary(
                plan,
                emptyList(),
                0L,
                1L,
                0L,
                1,
                progress,
            )
            .refined()
}
