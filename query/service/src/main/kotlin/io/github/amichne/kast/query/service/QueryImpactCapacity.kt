package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactSource
import io.github.amichne.kast.query.contract.QueryImpactStep
import io.github.amichne.kast.query.contract.retainedStorageBytes
import io.github.amichne.kast.relation.contract.ExactModelCallablePosition
import io.github.amichne.kast.relation.contract.ModelValuePosition
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueRole

/** Cheap detached charges precede prefix/model materialization, including when the native hop is cached. */
internal fun requiredImpactExpansionBytes(
    route: QueryImpactRoute,
    observation: ValueFlowStep,
    source: QueryImpactSource,
    retainedBytes: Long,
): Long {
    val branches = (route.representation as? QueryImpactRepresentation.Present)?.evidence?.branches?.size ?: 0
    val terminalReserve =
        saturatedAdd(
            route.retainedBytes,
            saturatedAdd((1..8).fold(0L) { bytes, _ -> saturatedAdd(bytes, route.site.retainedBytes) }, 8192L),
        )
    var required = retainedBytes
    for (transfer in observation.transfers) {
        val step = QueryImpactStep.Compiler(transfer).retainedStorageBytes()
        var next = saturatedAdd(route.retainedBytes, saturatedAdd(transfer.target.retainedBytes, 8192L))
        repeat(branches + 2) { next = saturatedAdd(next, step) }
        required = saturatedAdd(required, next)
    }
    for (obligation in observation.obligations) required = saturatedAdd(required, terminalReserve)
    required = saturatedAdd(required, reviewedImpactExpansionBytes(route, source, terminalReserve, branches))
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
): Long {
    var required = 0L
    val present = route.representation as? QueryImpactRepresentation.Present
    val argument = route.site.role as? ValueRole.Argument
    if (present != null && argument != null) {
        for (model in source.representationModels) {
            if (!model.matchesInput(argument)) continue
            val modelBytes = model.retainedStorageBytes()
            var next = saturatedAdd(terminalReserve, argument.call.resultSite().retainedBytes)
            repeat(branches + 2) { next = saturatedAdd(next, modelBytes) }
            required = saturatedAdd(required, next)
        }
    }
    for (model in source.boundaryModels) {
        if (model.source.site != route.site) continue
        var next = terminalReserve
        repeat(branches + 2) { next = saturatedAdd(next, model.retainedStorageBytes()) }
        required = saturatedAdd(required, next)
    }
    return required
}
