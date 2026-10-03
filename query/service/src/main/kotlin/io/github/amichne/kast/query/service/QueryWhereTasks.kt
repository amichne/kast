package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.ExactQueryStage

/** The existing predicate task retains resource admission and ordered stage expansion. */
internal suspend fun QueryReadStages.where(
    task: PipelineTask.Symbol,
    stage: ExactQueryStage.Where,
    state: QueryExecutionState,
    tasks: ArrayDeque<PipelineTask>,
): Boolean =
    when (val predicate = stage.predicate) {
        is io.github.amichne.kast.query.contract.QueryPredicate.Primitive -> {
            tasks.removeFirst()
            if (matchesPrimitive(task.value, predicate)) tasks.addFirst(task.copy(stage = stage.next))
            true
        }
        is io.github.amichne.kast.query.contract.QueryPredicate.Visibility ->
            when (val admitted = state.sourceResources()) {
                is Refinement.Rejected -> false
                is Refinement.Refined -> {
                    val values = whereVisibility(task.value, predicate, state, admitted.value)
                    tasks.removeFirst()
                    values.asReversed().forEach { tasks.addFirst(PipelineTask.Symbol(it, stage.next)) }
                    true
                }
            }
    }
