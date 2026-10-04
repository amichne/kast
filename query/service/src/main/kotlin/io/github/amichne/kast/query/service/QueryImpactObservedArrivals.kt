package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryImpactExecutionFailure
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause

internal fun compilerImpactArrivals(
    route: QueryImpactRoute,
    observation: ValueFlowStep,
): Refinement<List<PipelineTask>, QueryImpactExecutionFailure> {
    val tasks = mutableListOf<PipelineTask>()
    for (transfer in observation.transfers) {
        when (val branch = route.compiler(transfer)) {
            is Refinement.Rejected ->
                return Refinement.Rejected(QueryImpactExecutionFailure.Representation(branch.failure))
            is Refinement.Refined -> tasks += PipelineTask.ImpactExplore(branch.value)
        }
    }
    return Refinement.Refined(tasks)
}

internal fun observedImpactTerminals(
    route: QueryImpactRoute,
    observation: ValueFlowStep,
    modeled: Boolean,
): Refinement<List<QueryImpactPath>, QueryImpactExecutionFailure> {
    if (observation.transfers.isEmpty() && observation.obligations.isEmpty() && !modeled)
        return supportedEndPath(route, observation)
    val paths = mutableListOf<QueryImpactPath>()
    for (obligation in observation.obligations) {
        if (obligation.cause == ValueFlowUnsupportedCause.UNMODELED_CALL && modeled) continue
        when (val path = route.path(QueryImpactTerminal.Unresolved.Flow(obligation))) {
            is Refinement.Rejected -> return Refinement.Rejected(QueryImpactExecutionFailure.Path(path.failure))
            is Refinement.Refined -> paths += path.value
        }
    }
    return Refinement.Refined(paths)
}

private fun supportedEndPath(
    route: QueryImpactRoute,
    observation: ValueFlowStep,
): Refinement<List<QueryImpactPath>, QueryImpactExecutionFailure> {
    val terminal =
        when (val admitted = QueryImpactTerminal.SupportedDomainEnd.admit(observation)) {
            is Refinement.Rejected -> return Refinement.Rejected(QueryImpactExecutionFailure.Path(admitted.failure))
            is Refinement.Refined -> admitted.value
        }
    return when (val path = route.path(terminal)) {
        is Refinement.Rejected -> Refinement.Rejected(QueryImpactExecutionFailure.Path(path.failure))
        is Refinement.Refined -> Refinement.Refined(listOf(path.value))
    }
}
