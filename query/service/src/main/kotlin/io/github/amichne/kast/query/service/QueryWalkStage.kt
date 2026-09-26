package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QueryWalkArrival
import io.github.amichne.kast.query.contract.QueryWalkObservation
import io.github.amichne.kast.query.contract.merge
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalContinuation
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalPosition
import io.github.amichne.kast.traversal.contract.TraversalRecord
import io.github.amichne.kast.traversal.contract.TraversalResult

internal sealed interface QueryWalkStageResult {
    data object NotStarted : QueryWalkStageResult

    data object ContractRejected : QueryWalkStageResult

    data class Read(
        val symbols: List<QuerySymbol>,
        val continuation: TraversalContinuation?,
        val observation: QueryWalkObservation?,
    ) : QueryWalkStageResult
}

internal fun QueryWalkStageResult.Read.nextTasks(task: PipelineTask.Walk): List<PipelineTask> =
    symbols.map { PipelineTask.Symbol(it, task.stage.next) } +
        listOfNotNull(observation?.let(PipelineTask::WalkObservation)) +
        listOfNotNull(continuation?.let { task.copy(cursor = it) })

/** Query owns scheduling; the traversal domain owns breadth-first expansion and its continuation. */
internal class QueryWalkStage(
    private val traversal: TraversalOperations,
    private val ceiling: TraversalBudget,
) {
    suspend fun read(task: PipelineTask.Walk, state: QueryExecutionState): QueryWalkStageResult {
        val budget =
            state.traversalBudget(state.request.budget.resources.resultLimit.value, task.stage.maximumDepth, ceiling)
                ?: return QueryWalkStageResult.NotStarted
        val plan =
            if (task.cursor == null) {
                when (
                    val admitted =
                        TraversalPlan.start(task.value.selector, task.stage.meaning, budget, task.stage.strategy)
                ) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return QueryWalkStageResult.ContractRejected
                }
            } else {
                when (
                    val admitted =
                        TraversalPlan.resume(
                            task.value.selector,
                            task.stage.meaning,
                            budget,
                            task.cursor,
                            task.stage.strategy,
                        )
                ) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return QueryWalkStageResult.ContractRejected
                }
            }
        val result = traversal.run(plan)
        if (!result.matchesPlan(plan)) return QueryWalkStageResult.ContractRejected
        return result.toStageResult(task, state)
    }
}

private fun TraversalResult.matchesPlan(requested: TraversalPlan): Boolean =
    when (this) {
        is TraversalResult.Complete -> page.plan.matches(requested)
        is TraversalResult.Qualified -> page.plan.matches(requested)
        is TraversalResult.Rejected -> true
    }

private fun TraversalPlan.matches(requested: TraversalPlan): Boolean =
    start.fingerprint == requested.start.fingerprint &&
        start.lease == requested.start.lease &&
        scope == requested.scope &&
        meaning == requested.meaning &&
        budget == requested.budget &&
        strategy == requested.strategy &&
        identity == requested.identity &&
        position.matches(requested.position)

private fun TraversalPosition.matches(requested: TraversalPosition): Boolean =
    when (this) {
        TraversalPosition.Start -> requested is TraversalPosition.Start
        is TraversalPosition.Resume ->
            requested is TraversalPosition.Resume && continuation.fingerprint == requested.continuation.fingerprint
    }

private fun TraversalResult.toStageResult(
    task: PipelineTask.Walk,
    state: QueryExecutionState,
): QueryWalkStageResult.Read =
    when (this) {
        is TraversalResult.Complete -> {
            state.consume(page.examinedWorkUnits.value.coerceAtLeast(1L))
            state.observeTime()
            QueryWalkStageResult.Read(
                page.records.map { it.toQuerySymbol(task.value, state) },
                null,
                QueryWalkObservation.from(this),
            )
        }
        is TraversalResult.Qualified -> {
            state.consume(page.examinedWorkUnits.value.coerceAtLeast(1L))
            state.traversalLimited(qualification)
            state.observeTime()
            QueryWalkStageResult.Read(
                page.records.map { it.toQuerySymbol(task.value, state) },
                (qualification as? io.github.amichne.kast.traversal.contract.TraversalQualification.Resumable)
                    ?.continuation,
                QueryWalkObservation.from(this),
            )
        }
        is TraversalResult.Rejected -> {
            state.failure(QueryItemFailure.Walk(task.value.selector, task.stage.meaning, reason))
            state.limit(QueryLimitation.TRAVERSAL_INCOMPLETE)
            QueryWalkStageResult.Read(emptyList(), null, null)
        }
    }

private fun TraversalRecord.toQuerySymbol(input: QuerySymbol, state: QueryExecutionState): QuerySymbol =
    fact.toQuerySymbol(input.connections, state).let { expanded ->
        expanded.copy(
            arrival = input.arrival.merge(expanded.arrival),
            walkArrival = input.walkArrival.merge(QueryWalkArrival.Proven.one(this)),
        )
    }
