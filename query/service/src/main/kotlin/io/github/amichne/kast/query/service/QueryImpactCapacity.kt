package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactRetainedGraph
import io.github.amichne.kast.query.contract.QueryImpactSource
import io.github.amichne.kast.query.contract.QueryImpactStep
import io.github.amichne.kast.relation.contract.ExactModelCallablePosition
import io.github.amichne.kast.relation.contract.ModelValuePosition
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueRole

/** Cheap detached charges precede prefix/model materialization, including when the native hop is cached. */
internal fun requiredImpactExpansionBytes(
    route: QueryImpactRoute,
    observation: ValueFlowStep,
    source: QueryImpactSource,
    retainedBytes: (QueryImpactRetainedGraph) -> Long,
): Long {
    val graph = QueryImpactRetainedGraph()
    val current = retainedBytes(graph)
    val branches = (route.representation as? QueryImpactRepresentation.Present)?.evidence?.branches?.size ?: 0
    val terminalReserve =
        saturatedAdd(
            route.retainedBytes(graph),
            saturatedAdd(
                (1..8).fold(0L) { bytes, _ -> saturatedAdd(bytes, graph.site(route.site)) },
                saturatedAdd(8192L, futureImpactRouteStorageBytes(route)),
            ),
        )
    var required = current
    for (transfer in observation.transfers) {
        val step = QueryImpactStep.Compiler(transfer)
        var next =
            saturatedAdd(
                route.retainedBytes(graph),
                saturatedAdd(graph.site(transfer.target), saturatedAdd(8192L, futureImpactRouteStorageBytes(route))),
            )
        repeat(branches + 2) { next = saturatedAdd(next, graph.step(step)) }
        required = saturatedAdd(required, next)
    }
    for (obligation in observation.obligations) required = saturatedAdd(required, terminalReserve)
    required = saturatedAdd(required, reviewedImpactExpansionBytes(route, source, terminalReserve, branches, graph))
    return saturatedAdd(required, terminalReserve)
}

internal fun ExactModelCallablePosition.matches(argument: ValueRole.Argument): Boolean =
    position == ModelValuePosition.Argument(argument.position) &&
        endpoint.lease.identity == argument.call.callable.lease.identity &&
        endpoint.compilerIdentity == argument.call.callable.compilerIdentity &&
        endpoint.file == argument.call.callable.file &&
        endpoint.range == argument.call.callable.range

private fun reviewedImpactExpansionBytes(
    route: QueryImpactRoute,
    source: QueryImpactSource,
    terminalReserve: Long,
    branches: Int,
    graph: QueryImpactRetainedGraph,
): Long {
    var required = 0L
    val present = route.representation as? QueryImpactRepresentation.Present
    val argument = route.site.role as? ValueRole.Argument
    if (present != null && argument != null) {
        for (model in source.representationModels) {
            if (!model.matchesInput(argument)) continue
            val modelBytes = graph.representationModel(model)
            var next = saturatedAdd(terminalReserve, graph.site(argument.call.resultSite()))
            repeat(branches + 2) { next = saturatedAdd(next, modelBytes) }
            required = saturatedAdd(required, next)
        }
    }
    for (model in source.boundaryModels) {
        if (model.source.site != route.site) continue
        var next = terminalReserve
        repeat(branches + 2) { next = saturatedAdd(next, graph.boundaryModel(model)) }
        required = saturatedAdd(required, next)
    }
    return required
}

/**
 * The next route allocates its wrapper and a new prefix list, not copies of the immutable prefix witnesses.
 * Representation propagation additionally allocates Present/evidence/branch-set wrappers and, per branch, a new branch,
 * history list and history entry. Shared current states and transfer payloads stay in the ownership visitor.
 */
private fun futureImpactRouteStorageBytes(route: QueryImpactRoute): Long {
    var bytes = saturatedAdd(IMPACT_NODE_STORAGE_BYTES, impactListStorageBytes(route.steps.size.toLong() + 1L))
    val evidence = (route.representation as? QueryImpactRepresentation.Present)?.evidence
    if (evidence != null) {
        bytes =
            saturatedAdd(
                bytes,
                saturatedAdd(
                    IMPACT_NODE_STORAGE_BYTES * IMPACT_REPRESENTATION_WRAPPERS,
                    evidence.branches.size.toLong() * IMPACT_CELL_STORAGE_BYTES,
                ),
            )
        for (branch in evidence.branches) {
            bytes =
                saturatedAdd(
                    bytes,
                    saturatedAdd(
                        IMPACT_NODE_STORAGE_BYTES * 2L,
                        impactListStorageBytes(branch.history.size.toLong() + 1L),
                    ),
                )
        }
    }
    return if (bytes > Long.MAX_VALUE / IMPACT_STORAGE_MULTIPLIER) Long.MAX_VALUE else bytes * IMPACT_STORAGE_MULTIPLIER
}

private fun impactListStorageBytes(size: Long): Long =
    saturatedAdd(IMPACT_NODE_STORAGE_BYTES, size * IMPACT_CELL_STORAGE_BYTES)

private const val IMPACT_NODE_STORAGE_BYTES = 512L
private const val IMPACT_CELL_STORAGE_BYTES = 8L
private const val IMPACT_STORAGE_MULTIPLIER = 8L

private const val IMPACT_REPRESENTATION_WRAPPERS = 3L
