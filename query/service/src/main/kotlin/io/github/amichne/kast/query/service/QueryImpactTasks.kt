package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryImpactExecutionFailure
import io.github.amichne.kast.query.contract.QueryImpactExecutionStop
import io.github.amichne.kast.query.contract.QueryImpactLedger
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactReadRejection
import io.github.amichne.kast.query.contract.QueryImpactRetainedGraph
import io.github.amichne.kast.query.contract.QueryImpactScopeExclusion
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowCompilerPort
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep

internal sealed interface QueryImpactTaskTransition {
    data object Advanced : QueryImpactTaskTransition

    data object NotStarted : QueryImpactTaskTransition

    data class Rejected(val failure: QueryImpactExecutionFailure) : QueryImpactTaskTransition
}

/** This stage advances tasks and detached evidence inside the existing query interpreter. */
internal class QueryImpactTasks(
    private val request: QueryExecutionRequest,
    private val state: QueryExecutionState,
    private val tasks: ArrayDeque<PipelineTask>,
    private val port: ValueFlowCompilerPort,
    checkpoint: QueryImpactSnapshot?,
) {
    private val reads = checkpoint?.reads?.toMutableMap() ?: linkedMapOf()
    private val paths = checkpoint?.paths?.toMutableList() ?: mutableListOf()
    private var ledger = checkpoint?.ledger
    private val source
        get() = (request.plan as AdmittedQueryPlan.Impact).source

    fun snapshot(): QueryImpactSnapshot =
        QueryImpactSnapshot(
            java.util.Collections.unmodifiableMap(reads.toMap()),
            java.util.Collections.unmodifiableList(paths.toList()),
            ledger,
        )

    fun rowsState(): QueryImpactRowsState {
        val investigated = ledger ?: return QueryImpactRowsState.Pending
        return when (val admitted = QueryRows.ValuePaths.fromInvestigation(investigated)) {
            is Refinement.Rejected ->
                QueryImpactRowsState.Rejected(QueryImpactExecutionFailure.Accounting(admitted.failure))
            is Refinement.Refined -> QueryImpactRowsState.Ready(admitted.value)
        }
    }

    suspend fun explore(
        task: PipelineTask.ImpactExplore,
        retainedBytes: (QueryImpactRetainedGraph) -> Long,
    ): QueryImpactTaskTransition {
        if (!state.canProcessImpact()) return QueryImpactTaskTransition.NotStarted
        val route = task.route
        val cycle = QueryImpactExecutionStop.Cycle.admit(route.producer, route.steps)
        if (cycle is Refinement.Refined)
            return finishRoute(route, QueryImpactTerminal.Unresolved.ExecutionStop(cycle.value))
        val explicit = source.domain as? RelationSearchBoundary.Explicit
        if (explicit != null) {
            val outside = QueryImpactScopeExclusion.admit(route.site, explicit)
            if (outside is Refinement.Refined)
                return finishRoute(route, QueryImpactTerminal.ExplicitScopeExclusion(outside.value))
        }
        val read =
            when (val acquired = acquireRead(route, retainedBytes)) {
                QueryImpactReadAcquisition.NotStarted -> return QueryImpactTaskTransition.NotStarted
                is QueryImpactReadAcquisition.Cutoff ->
                    return finishRoute(route, QueryImpactTerminal.Unresolved.ExecutionStop(acquired.stop))
                is QueryImpactReadAcquisition.Ready -> acquired.read
            }
        return when (read) {
            is ValueFlowRead.Rejected ->
                finishRoute(
                    route,
                    QueryImpactTerminal.Unresolved.ReadRejected(
                        QueryImpactReadRejection.Native(route.site, source.domain, read.cause, read.examinedWorkUnits)
                    ),
                )
            is ValueFlowRead.ContractRejected ->
                finishRoute(
                    route,
                    QueryImpactTerminal.Unresolved.ReadRejected(
                        QueryImpactReadRejection.Contract(route.site, source.domain, read.cause, read.examinedWorkUnits)
                    ),
                )
            is ValueFlowRead.Observed -> observed(route, read.step, retainedBytes)
        }
    }

    fun finalizeInvestigation(): QueryImpactTaskTransition {
        val rejections = reads.mapNotNull { (site, read) ->
            when (read) {
                is ValueFlowRead.Observed -> null
                is ValueFlowRead.Rejected ->
                    QueryImpactReadRejection.Native(site, source.domain, read.cause, read.examinedWorkUnits)
                is ValueFlowRead.ContractRejected ->
                    QueryImpactReadRejection.Contract(site, source.domain, read.cause, read.examinedWorkUnits)
            }
        }
        val admitted =
            QueryImpactLedger.fromEvidence(
                source.producers.map { it.site },
                source.domain,
                source.semantics,
                source.representationModels,
                source.boundaryModels,
                reads.values.filterIsInstance<ValueFlowRead.Observed>().map { it.step },
                paths,
                rejections,
                source.producers,
            )
        if (admitted is Refinement.Rejected)
            return QueryImpactTaskTransition.Rejected(QueryImpactExecutionFailure.Ledger(admitted.failure))
        ledger = (admitted as Refinement.Refined).value
        tasks.removeFirst()
        paths.asReversed().forEach { tasks.addFirst(PipelineTask.ValuePath(it)) }
        return QueryImpactTaskTransition.Advanced
    }

    private fun observed(
        route: QueryImpactRoute,
        observation: ValueFlowStep,
        retainedBytes: (QueryImpactRetainedGraph) -> Long,
    ): QueryImpactTaskTransition {
        val required = requiredImpactExpansionBytes(route, observation, source, retainedBytes)
        if (required > request.budget.checkpointBytes.value) {
            val cutoff = impactCapacityStop(route, required, request.budget.checkpointBytes.value)
            return finishRoute(route, QueryImpactTerminal.Unresolved.ExecutionStop(cutoff))
        }
        val compilerTasks =
            when (val branches = compilerImpactArrivals(route, observation)) {
                is Refinement.Rejected -> return QueryImpactTaskTransition.Rejected(branches.failure)
                is Refinement.Refined -> branches.value
            }
        val modeled = modeledImpactArrivals(route, source)
        if (modeled is Refinement.Rejected) return QueryImpactTaskTransition.Rejected(modeled.failure)
        val arrivals = (modeled as Refinement.Refined).value
        val terminals =
            when (val admitted = observedImpactTerminals(route, observation, arrivals.applied)) {
                is Refinement.Rejected -> return QueryImpactTaskTransition.Rejected(admitted.failure)
                is Refinement.Refined -> admitted.value
            }
        val next = compilerTasks + arrivals.tasks
        (arrivals.paths + terminals).forEach(::record)
        tasks.removeFirst()
        next.asReversed().forEach(tasks::addFirst)
        return QueryImpactTaskTransition.Advanced
    }

    private fun finishRoute(route: QueryImpactRoute, terminal: QueryImpactTerminal): QueryImpactTaskTransition =
        when (val path = route.path(terminal)) {
            is Refinement.Rejected -> QueryImpactTaskTransition.Rejected(QueryImpactExecutionFailure.Path(path.failure))
            is Refinement.Refined -> {
                record(path.value)
                tasks.removeFirst()
                QueryImpactTaskTransition.Advanced
            }
        }

    private fun record(path: QueryImpactPath) {
        if (path !in paths) paths += path
    }

    private suspend fun acquireRead(
        route: QueryImpactRoute,
        retainedBytes: (QueryImpactRetainedGraph) -> Long,
    ): QueryImpactReadAcquisition {
        val cached = reads[route.site]
        if (cached != null) return QueryImpactReadAcquisition.Ready(cached)
        val graph = QueryImpactRetainedGraph()
        val current = retainedBytes(graph)
        val remaining = (request.budget.checkpointBytes.value - current).coerceAtLeast(0L)
        val itemBytes = saturatedAdd(route.retainedBytes(graph), saturatedAdd(graph.site(route.site), 4096L))
        val reserve = saturatedAdd(itemBytes, itemBytes)
        if (remaining < reserve)
            return QueryImpactReadAcquisition.Cutoff(
                impactCapacityStop(route, saturatedAdd(current, reserve), request.budget.checkpointBytes.value)
            )
        val budget = state.impactBudget(remaining / 2L, itemBytes) ?: return QueryImpactReadAcquisition.NotStarted
        val requested = ValueFlowRequest(route.site, budget, source.domain)
        val read = validateImpactRead(port.read(requested), requested, source)
        state.consume(read.examinedImpactWork().value)
        reads[route.site] = read
        return QueryImpactReadAcquisition.Ready(read)
    }
}

private fun <V, F> Refinement<V, F>.impactRefined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("A proven impact invariant was rejected: $failure")
    }

internal val unavailableValueFlowPort = ValueFlowCompilerPort {
    ValueFlowRead.Rejected(ValueFlowRejection.NATIVE_UNAVAILABLE, RelationWorkCount.parse(0).impactRefined())
}
