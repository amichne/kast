package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryImpactExecutionFailure
import io.github.amichne.kast.query.contract.QueryImpactLedger
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactPathFailure
import io.github.amichne.kast.query.contract.QueryImpactProducer
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactRetainedGraph
import io.github.amichne.kast.query.contract.QueryImpactSource
import io.github.amichne.kast.query.contract.QueryImpactStep
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.relation.contract.RepresentationEvidence
import io.github.amichne.kast.relation.contract.RepresentationPropagationFailure
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer

private const val IMPACT_CONTAINER_STORAGE_BYTES = 512L

/** Detached routes are tasks in the one query interpreter, never independent continuation tokens. */
internal data class QueryImpactRoute(
    val producer: ValueSite,
    val steps: List<QueryImpactStep>,
    val representation: QueryImpactRepresentation,
) {
    val site: ValueSite
        get() = steps.lastOrNull()?.target ?: producer

    val retainedBytes: Long
        get() = retainedBytes(QueryImpactRetainedGraph())

    fun retainedBytes(graph: QueryImpactRetainedGraph): Long =
        saturatedAdd(IMPACT_CONTAINER_STORAGE_BYTES, graph.route(producer, steps, representation))

    fun path(terminal: QueryImpactTerminal): Refinement<QueryImpactPath, QueryImpactPathFailure> =
        QueryImpactPath.fromEvidence(producer, steps, representation, terminal)

    fun compiler(transfer: ValueTransfer): Refinement<QueryImpactRoute, RepresentationPropagationFailure> =
        when (val current = representation) {
            QueryImpactRepresentation.NotModeled ->
                Refinement.Refined(copy(steps = steps + QueryImpactStep.Compiler(transfer)))
            is QueryImpactRepresentation.Present ->
                when (val next = current.evidence.transfer(transfer)) {
                    is Refinement.Rejected -> next
                    is Refinement.Refined ->
                        Refinement.Refined(
                            copy(
                                steps = steps + QueryImpactStep.Compiler(transfer),
                                representation = QueryImpactRepresentation.Present(next.value),
                            )
                        )
                }
        }

    companion object {
        fun initial(producer: QueryImpactProducer, source: QueryImpactSource): List<QueryImpactRoute> {
            val evidence =
                source.representationModels.filterIsInstance<RepresentationRule.Origin>().mapNotNull { rule ->
                    when (val origin = RepresentationEvidence.origin(producer.site, producer.invocation, rule)) {
                        is Refinement.Rejected -> null
                        is Refinement.Refined -> origin.value
                    }
                }
            return if (evidence.isEmpty())
                listOf(QueryImpactRoute(producer.site, emptyList(), QueryImpactRepresentation.NotModeled))
            else evidence.map { QueryImpactRoute(producer.site, emptyList(), QueryImpactRepresentation.Present(it)) }
        }
    }
}

/** Copies are detached before entering the existing checkpoint store and retain full native and model witnesses. */
internal data class QueryImpactSnapshot(
    val reads: Map<ValueSite, ValueFlowRead> = emptyMap(),
    val paths: List<QueryImpactPath> = emptyList(),
    val ledger: QueryImpactLedger? = null,
) {
    val retainedBytes: Long
        get() = retainedBytes(QueryImpactRetainedGraph())

    fun retainedBytes(graph: QueryImpactRetainedGraph): Long =
        saturatedAdd(
            paths.fold(IMPACT_CONTAINER_STORAGE_BYTES) { bytes, path -> saturatedAdd(bytes, graph.path(path)) },
            saturatedAdd(
                reads.entries.fold(IMPACT_CONTAINER_STORAGE_BYTES) { bytes, entry ->
                    saturatedAdd(
                        bytes,
                        saturatedAdd(
                            IMPACT_CONTAINER_STORAGE_BYTES,
                            saturatedAdd(graph.site(entry.key), graph.read(entry.value)),
                        ),
                    )
                },
                ledger?.let(graph::ledger) ?: 0L,
            ),
        )
}

internal sealed interface QueryImpactRowsState {
    data object Pending : QueryImpactRowsState

    data class Ready(val rows: QueryRows.ValuePaths) : QueryImpactRowsState

    data class Rejected(val failure: QueryImpactExecutionFailure) : QueryImpactRowsState
}
