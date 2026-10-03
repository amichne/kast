package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryImpactExecutionFailure
import io.github.amichne.kast.query.contract.QueryImpactModelHistoryFailure
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactSource
import io.github.amichne.kast.query.contract.QueryImpactStep
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.relation.contract.BoundaryArrival
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.RepresentationEvidence
import io.github.amichne.kast.relation.contract.RepresentationModelApplication
import io.github.amichne.kast.relation.contract.RepresentationPropagationFailure
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.ValueRole

internal data class QueryImpactModelArrivals(val tasks: List<PipelineTask>, val paths: List<QueryImpactPath>) {
    val applied: Boolean
        get() = tasks.isNotEmpty() || paths.isNotEmpty()
}

/** Pure reviewed-model application; task and retained-state mutation stays in the existing interpreter. */
internal fun modeledImpactArrivals(
    route: QueryImpactRoute,
    source: QueryImpactSource,
): Refinement<QueryImpactModelArrivals, QueryImpactExecutionFailure> {
    val tasks = mutableListOf<PipelineTask>()
    val paths = mutableListOf<QueryImpactPath>()
    val present = route.representation as? QueryImpactRepresentation.Present
    val argument = route.site.role as? ValueRole.Argument
    if (present != null && argument != null) {
        for (model in source.representationModels.filter { it.matchesInput(argument) }) {
            when (val arrival = representationArrival(route, present.evidence, argument, model)) {
                is Refinement.Rejected -> return arrival
                is Refinement.Refined -> {
                    tasks += arrival.value.tasks
                    paths += arrival.value.paths
                }
            }
        }
    }
    for (model in source.boundaryModels.filter { it.source.site == route.site }) {
        when (val arrival = boundaryArrival(route, model)) {
            is Refinement.Rejected -> return arrival
            is Refinement.Refined -> {
                tasks += arrival.value.tasks
                paths += arrival.value.paths
            }
        }
    }
    return Refinement.Refined(QueryImpactModelArrivals(tasks, paths))
}

private fun representationArrival(
    route: QueryImpactRoute,
    evidence: RepresentationEvidence,
    argument: ValueRole.Argument,
    model: RepresentationRule,
): Refinement<QueryImpactModelArrivals, QueryImpactExecutionFailure> =
    when (model) {
        is RepresentationRule.Origin -> Refinement.Refined(QueryImpactModelArrivals(emptyList(), emptyList()))
        is RepresentationRule.ConsumerExpectation ->
            when (val expected = evidence.expect(model)) {
                is Refinement.Rejected ->
                    Refinement.Rejected(QueryImpactExecutionFailure.Representation(expected.failure))
                is Refinement.Refined -> terminalArrival(route, QueryImpactTerminal.Consumer(expected.value))
            }
        is RepresentationRule.Transfer ->
            modeledRepresentation(
                route,
                evidence.modeledTransfer(argument.call.resultSite(), argument.call, model),
            )
        is RepresentationRule.Transformation ->
            modeledRepresentation(
                route,
                evidence.transform(argument.call.resultSite(), argument.call, model),
            )
    }

private fun modeledRepresentation(
    route: QueryImpactRoute,
    evidence: Refinement<RepresentationEvidence, RepresentationPropagationFailure>,
): Refinement<QueryImpactModelArrivals, QueryImpactExecutionFailure> {
    val next =
        when (evidence) {
            is Refinement.Rejected ->
                return Refinement.Rejected(QueryImpactExecutionFailure.Representation(evidence.failure))
            is Refinement.Refined -> evidence.value
        }
    val applications = next.branches.map { it.history.last() }.distinct()
    val application =
        applications.singleOrNull() as? RepresentationModelApplication
            ?: return Refinement.Rejected(
                QueryImpactExecutionFailure.ModelHistory(QueryImpactModelHistoryFailure.MISSING_APPLICATION)
            )
    return Refinement.Refined(
        QueryImpactModelArrivals(
            listOf(
                PipelineTask.ImpactExplore(
                    route.copy(
                        steps = route.steps + QueryImpactStep.ModeledRepresentation(application),
                        representation = QueryImpactRepresentation.Present(next),
                    )
                )
            ),
            emptyList(),
        )
    )
}

private fun boundaryArrival(
    route: QueryImpactRoute,
    model: BoundaryModel,
): Refinement<QueryImpactModelArrivals, QueryImpactExecutionFailure> =
    when (model) {
        is BoundaryModel.Terminal ->
            when (val arrival = BoundaryArrival.terminal(model.source, model)) {
                is Refinement.Rejected -> Refinement.Rejected(QueryImpactExecutionFailure.Boundary(arrival.failure))
                is Refinement.Refined -> terminalArrival(route, QueryImpactTerminal.ModeledTerminal(arrival.value))
            }
        is BoundaryModel.Continuation ->
            when (val arrival = BoundaryArrival.connect(model.source, model)) {
                is Refinement.Rejected -> Refinement.Rejected(QueryImpactExecutionFailure.Boundary(arrival.failure))
                is Refinement.Refined -> connectedArrival(route, arrival.value)
            }
    }

private fun connectedArrival(
    route: QueryImpactRoute,
    connection: BoundaryArrival.Connected,
): Refinement<QueryImpactModelArrivals, QueryImpactExecutionFailure> {
    val representation =
        when (val current = route.representation) {
            QueryImpactRepresentation.NotModeled -> QueryImpactRepresentation.NotModeled
            is QueryImpactRepresentation.Present ->
                when (val next = current.evidence.throughBoundary(connection)) {
                    is Refinement.Rejected ->
                        return Refinement.Rejected(QueryImpactExecutionFailure.Representation(next.failure))
                    is Refinement.Refined -> QueryImpactRepresentation.Present(next.value)
                }
        }
    return Refinement.Refined(
        QueryImpactModelArrivals(
            listOf(
                PipelineTask.ImpactExplore(
                    route.copy(
                        steps = route.steps + QueryImpactStep.ModeledBoundary(connection),
                        representation = representation,
                    )
                )
            ),
            emptyList(),
        )
    )
}

private fun terminalArrival(
    route: QueryImpactRoute,
    terminal: QueryImpactTerminal,
): Refinement<QueryImpactModelArrivals, QueryImpactExecutionFailure> =
    when (val path = route.path(terminal)) {
        is Refinement.Rejected -> Refinement.Rejected(QueryImpactExecutionFailure.Path(path.failure))
        is Refinement.Refined -> Refinement.Refined(QueryImpactModelArrivals(emptyList(), listOf(path.value)))
    }

internal fun RepresentationRule.matchesInput(argument: ValueRole.Argument): Boolean =
    when (this) {
        is RepresentationRule.Origin -> false
        is RepresentationRule.ConsumerExpectation -> input.matches(argument)
        is RepresentationRule.Transfer -> input.matches(argument)
        is RepresentationRule.Transformation -> input.matches(argument)
    }
