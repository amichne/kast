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
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowCompilerPort
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowSuspensionCause
import io.github.amichne.kast.relation.contract.ValueFlowWorkReceipt

internal sealed interface QueryImpactTaskTransition {
    data object Advanced : QueryImpactTaskTransition

    data object NotStarted : QueryImpactTaskTransition

    data object Suspended : QueryImpactTaskTransition

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
    private val receipts = checkpoint?.receipts?.toMutableMap() ?: linkedMapOf()
    private val remainders = checkpoint?.remainders?.toMutableMap() ?: linkedMapOf()
    private val readRejections = checkpoint?.readRejections?.toMutableMap() ?: linkedMapOf()
    private val paths = checkpoint?.paths?.toMutableList() ?: mutableListOf()
    private var ledger = checkpoint?.ledger
    private val source
        get() = (request.plan as AdmittedQueryPlan.Impact).source

    fun snapshot(): QueryImpactSnapshot =
        QueryImpactSnapshot(
            java.util.Collections.unmodifiableMap(reads.toMap()),
            java.util.Collections.unmodifiableList(paths.toList()),
            ledger,
            java.util.Collections.unmodifiableMap(remainders.toMap()),
            java.util.Collections.unmodifiableMap(readRejections.toMap()),
            java.util.Collections.unmodifiableMap(receipts.toMap()),
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
                is QueryImpactReadAcquisition.Suspended -> {
                    state.limit(
                        when (acquired.cause) {
                            ValueFlowSuspensionCause.WORK_LIMIT_REACHED -> QueryLimitation.WORK_LIMIT_REACHED
                            ValueFlowSuspensionCause.RESULT_LIMIT_REACHED -> QueryLimitation.RESULT_LIMIT_REACHED
                            ValueFlowSuspensionCause.BYTE_LIMIT_REACHED -> QueryLimitation.BYTE_LIMIT_REACHED
                            ValueFlowSuspensionCause.TIME_LIMIT_REACHED -> QueryLimitation.TIME_LIMIT_REACHED
                        }
                    )
                    return QueryImpactTaskTransition.Suspended
                }
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
            is ValueFlowRead.Suspended -> error("Suspension is acquired before route expansion")
        }
    }

    fun finalizeInvestigation(): QueryImpactTaskTransition {
        val rejections = reads.mapNotNull { (site, read) ->
            when (read) {
                is ValueFlowRead.Observed -> null
                is ValueFlowRead.Suspended -> error("Finalization cannot retain a native suspension")
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
                rejections + readRejections.values,
                source.producers,
                source.requestedSites,
                source.peerBoundaries,
                receipts,
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
        val rejected =
            readRejections[route.site]
                ?.let { rejection ->
                    when (val path = route.path(QueryImpactTerminal.Unresolved.ReadRejected(rejection))) {
                        is Refinement.Refined -> listOf(path.value)
                        is Refinement.Rejected ->
                            return QueryImpactTaskTransition.Rejected(QueryImpactExecutionFailure.Path(path.failure))
                    }
                }
                .orEmpty()
        (arrivals.paths + terminals + rejected).forEach(::record)
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
        if (cached != null && route.site !in remainders) return QueryImpactReadAcquisition.Ready(cached)
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
        val requested = ValueFlowRequest(route.site, budget, source.domain, remainders[route.site])
        val read = shareRead(validateImpactRead(port.read(requested), requested, source))
        recordReceipt(route, requested, read)
        state.consume(read.examinedImpactWork().value)
        return retainRead(route, read, cached)
    }

    private fun shareRead(read: ValueFlowRead): ValueFlowRead {
        val previous = reads.values.filterIsInstance<ValueFlowRead.Observed>().map { it.step }
        return when (read) {
            is ValueFlowRead.Observed -> ValueFlowRead.Observed(read.step.shareCallableEvidence(previous))
            is ValueFlowRead.Suspended -> read.copy(step = read.step.shareCallableEvidence(previous))
            is ValueFlowRead.Rejected,
            is ValueFlowRead.ContractRejected -> read
        }
    }

    private fun recordReceipt(route: QueryImpactRoute, request: ValueFlowRequest, read: ValueFlowRead) {
        val actual =
            when (read) {
                is ValueFlowRead.Observed -> read.step.receipts
                is ValueFlowRead.Suspended -> read.step.receipts
                is ValueFlowRead.Rejected -> listOf(ValueFlowWorkReceipt.rejected(request, read.examinedWorkUnits))
                is ValueFlowRead.ContractRejected ->
                    listOf(ValueFlowWorkReceipt.rejected(request, read.examinedWorkUnits))
            }
        val prior = receipts[route.site]
        receipts[route.site] = if (prior == null) actual else java.util.Collections.unmodifiableList(prior + actual)
    }

    private fun retainRead(
        route: QueryImpactRoute,
        read: ValueFlowRead,
        cached: ValueFlowRead?,
    ): QueryImpactReadAcquisition {
        val page =
            when (read) {
                is ValueFlowRead.Observed -> read.step
                is ValueFlowRead.Suspended -> read.step
                is ValueFlowRead.Rejected,
                is ValueFlowRead.ContractRejected -> return retainRejection(route, read, cached)
            }
        val accumulated =
            when (
                val next = if (cached is ValueFlowRead.Observed) cached.step.append(page) else Refinement.Refined(page)
            ) {
                is Refinement.Refined -> next.value
                is Refinement.Rejected ->
                    return retainRejection(
                        route,
                        ValueFlowRead.ContractRejected(next.failure, page.examinedWorkUnits),
                        cached,
                    )
            }
        reads[route.site] = ValueFlowRead.Observed(accumulated)
        receipts[route.site] = accumulated.receipts
        if (read is ValueFlowRead.Suspended) {
            remainders[route.site] = read.remainder
            return QueryImpactReadAcquisition.Suspended(read.cause)
        }
        remainders.remove(route.site)
        return QueryImpactReadAcquisition.Ready(reads.getValue(route.site))
    }

    private fun retainRejection(
        route: QueryImpactRoute,
        read: ValueFlowRead,
        cached: ValueFlowRead?,
    ): QueryImpactReadAcquisition {
        remainders.remove(route.site)
        if (cached !is ValueFlowRead.Observed) {
            reads[route.site] = read
            return QueryImpactReadAcquisition.Ready(read)
        }
        readRejections[route.site] =
            when (read) {
                is ValueFlowRead.Rejected ->
                    QueryImpactReadRejection.Native(
                        route.site,
                        source.domain,
                        read.cause,
                        read.examinedWorkUnits,
                    )
                is ValueFlowRead.ContractRejected ->
                    QueryImpactReadRejection.Contract(
                        route.site,
                        source.domain,
                        read.cause,
                        read.examinedWorkUnits,
                    )
                else -> error("Only rejected reads can qualify an unfinished prefix")
            }
        return QueryImpactReadAcquisition.Ready(cached)
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
