package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryCheckpointStorageObservation
import io.github.amichne.kast.query.contract.QueryCompositionInput
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryExecutionRejection
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactRetainedGraph
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOccurrence
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryPresentationExecution
import io.github.amichne.kast.query.contract.QueryRelationObservation
import io.github.amichne.kast.query.contract.QueryRelationOmission
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QueryTerminalReason
import io.github.amichne.kast.query.contract.QueryWalkObservation
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationReferenceOccurrence
import io.github.amichne.kast.relation.contract.ValueFlowCompilerPort
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolExactOperations
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalOperations

/** In-process evaluator for the closed compositional exact-symbol query algebra. */
class QueryService(
    discovery: SymbolDiscoveryOperations,
    exact: SymbolExactOperations,
    source: SourceReadOperations,
    relations: RelationOperations,
    traversal: TraversalOperations,
    private val traversalCeiling: TraversalBudget,
    private val clock: QueryNanoClock = SystemQueryNanoClock,
    private val valueFlow: ValueFlowCompilerPort = unavailableValueFlowPort,
    private val presentation: QueryPresentationExecution? = null,
    private val checkpointObservation: QueryCheckpointStorageObservation = QueryCheckpointStorageObservation.None,
) : QueryOperations {
    private val stages = QueryReadStages(discovery, exact, source)
    private val traceMembers = QueryTraceMembers(source, stages)
    private val relationStage = QueryRelationStage(relations)
    private val walkStage = QueryWalkStage(traversal, traversalCeiling)

    override suspend fun run(request: QueryExecutionRequest): QueryExecutionResult {
        if (presentation?.admitValuePath() is Refinement.Rejected) {
            return QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION)
        }
        val checkpoint = request.checkpoint
        if (checkpoint != null && checkpoint !is PipelineCheckpoint) {
            return QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION)
        }
        if (request.plan.exceedsTraversalDepth(traversalCeiling.extent)) {
            return QueryExecutionResult.Rejected(QueryExecutionRejection.BUDGET_REJECTED)
        }
        return Execution(request, checkpoint).run()
    }

    /** The single evaluator keeps task order, page accounting, and continuation construction in one owner. */
    @Suppress("LargeClass")
    private inner class Execution(private val request: QueryExecutionRequest, checkpoint: PipelineCheckpoint?) {
        private val emittedBefore = checkpoint?.emittedCount ?: 0.queryCount()
        private val state = QueryExecutionState(request, clock)
        private var seed: PipelineSeed = checkpoint?.seed ?: PipelineSeed.Unmeasured
        private val tasks = ArrayDeque(checkpoint?.tasks ?: initialTasks(request.plan))
        private val identityRows = QueryIdentityRows(checkpoint?.identityRows.orEmpty())
        private val joinStage = QueryJoinStage(request, state, tasks, checkpoint?.joinState)
        private val impactTasks = QueryImpactTasks(request, state, tasks, valueFlow, checkpoint?.impact)
        private val symbols = mutableListOf<QuerySymbol>()
        private val valuePaths = mutableListOf<QueryImpactPath>()
        private val occurrences = mutableListOf<QueryOccurrence>()
        private val referenceObservations = mutableListOf<RelationReferenceOccurrence>()
        private val completedFailures = mutableListOf<QueryItemFailure>()
        private val completedOmissions = mutableListOf<QueryRelationOmission>()
        private val completedWalkObservations = mutableListOf<QueryWalkObservation>()
        private val relationObservations = mutableListOf<QueryRelationObservation>()
        private val discoveryTasks = QueryDiscoveryTasks(state, tasks)
        private var progressed = false
        private var terminal: QueryTerminalReason? = null
        private var rejection: QueryExecutionResult.Rejection? = null
        private val callbackOutput =
            QueryCallbackEvidenceTasks(presentation, tasks, ::emit) { rejection = QueryExecutionResult.Rejected(it) }

        init {
            state.limitations += checkpoint?.limitations.orEmpty()
            state.upstreamLimitations += checkpoint?.limitations.orEmpty()
            (request.plan as? AdmittedQueryPlan.Retained)?.source?.let(state::inheritRetainedLimitations)
        }

        suspend fun run(): QueryExecutionResult {
            while (tasks.isNotEmpty()) {
                if (!executeNext()) break
            }
            return (rejection ?: finish()).observedWork(state.consumedWork())
        }

        private suspend fun executeNext(): Boolean {
            if (
                tasks.first() !is PipelineTask.RelationObservation &&
                    symbols.size +
                        valuePaths.size +
                        occurrences.size +
                        joinStage.bindingRows.size +
                        completedFailures.size +
                        completedOmissions.size +
                        completedWalkObservations.size >= request.budget.resources.resultLimit.value
            ) {
                state.limit(QueryLimitation.RESULT_LIMIT_REACHED)
                return false
            }
            val task = tasks.first()
            if (!state.canContinue(task.needsWork()) || !advance(task)) return false
            state.drainFailures().asReversed().forEach { tasks.addFirst(PipelineTask.Failure(it)) }
            progressed = true
            return terminal == null && rejection == null
        }

        private suspend fun advance(task: PipelineTask): Boolean =
            when (task) {
                is PipelineTask.TraceTask -> traceMembers.advance(task, state, tasks)
                is PipelineTask.ImpactExplore -> impactTransition(impactTasks.explore(task, ::retainedBytes))
                PipelineTask.ImpactFinalize -> impactTransition(impactTasks.finalizeInvestigation())
                is PipelineTask.ValuePath -> emitValuePath(task.value)
                is PipelineTask.Failure -> emit(task.value.projectedUtf8Size()) { completedFailures += task.value }
                is PipelineTask.Omission -> emit(task.value.projectedUtf8Size()) { completedOmissions += task.value }
                is PipelineTask.WalkObservation -> callbackOutput.walk(task) { completedWalkObservations += task.value }
                is PipelineTask.Occurrence -> emit(task.value.projectedUtf8Size()) { occurrences += task.value }
                is PipelineTask.ReferenceObservation ->
                    emit(task.value.projectedUtf8Size()) { referenceObservations += task.value }
                is PipelineTask.DiscoveryObservation -> discoveryTasks.observation(task, ::emit)
                is PipelineTask.RelationObservation ->
                    if (task.value in relationObservations) {
                        tasks.removeFirst()
                        true
                    } else callbackOutput.relation(task) { relationObservations += task.value }
                is PipelineTask.WalkRecord -> emit(task.value.projectedUtf8Size()) { symbols += task.value }
                is PipelineTask.Candidate -> candidate(task)
                is PipelineTask.Symbol -> symbol(task)
                is PipelineTask.Binding ->
                    QueryBindingTasks.advance(task, state, tasks, ::emit, joinStage::recordBinding)
                is PipelineTask.Related -> related(task)
                is PipelineTask.Walk -> walk(task)
                is PipelineTask.Feed -> feed(task)
                is PipelineTask.FlushSet -> flushSet(task)
                is PipelineTask.FlushDistinct -> flushDistinct(task)
                is PipelineTask.JoinEvidence -> joinStage.emitRightEvidence(task)
                is PipelineTask.Join ->
                    when (val result = joinStage.advance(task)) {
                        is Refinement.Refined -> result.value
                        is Refinement.Rejected -> identityRejected(result.failure)
                    }
                is PipelineTask.Discover ->
                    discovered(stages.discover(task.syntax, state, task.remainder), task.next, task)
                is PipelineTask.DiscoverLocation -> discovered(stages.discoverLocation(task.target, state), task.next)
                is PipelineTask.DiscoverText -> discovered(stages.discoverText(task.syntax, state), task.next)
                is PipelineTask.Revalidate -> {
                    if (!state.consumeUnit()) false
                    else {
                        val values = stages.revalidate(task.selector, state)
                        tasks.removeFirst()
                        values.asReversed().forEach { tasks.addFirst(PipelineTask.Symbol(it, task.next)) }
                        true
                    }
                }
            }

        private fun impactTransition(transition: QueryImpactTaskTransition): Boolean =
            when (transition) {
                QueryImpactTaskTransition.Advanced -> true
                QueryImpactTaskTransition.NotStarted -> false
                QueryImpactTaskTransition.Suspended -> {
                    progressed = true
                    false
                }
                is QueryImpactTaskTransition.Rejected -> {
                    rejection = QueryExecutionResult.ImpactRejected(transition.failure)
                    false
                }
            }

        private fun retainedBytes(graph: QueryImpactRetainedGraph): Long =
            checkpoint(emittedBefore).retainedBytes(graph)

        private fun discovered(
            result: DiscoveryExecution,
            next: ExactQueryStage,
            producer: PipelineTask.Discover? = null,
        ): Boolean =
            when (val transition = discoveryTasks.discovered(result, next, producer)) {
                QueryDiscoveryTaskTransition.NotStarted,
                QueryDiscoveryTaskTransition.ContractRejected -> false
                QueryDiscoveryTaskTransition.Advanced -> true
                is QueryDiscoveryTaskTransition.Rejected -> {
                    rejection = transition.result
                    true
                }
            }

        private fun feed(task: PipelineTask.Feed): Boolean {
            (task.stage.input as? QueryCompositionInput.Retained)?.result?.let(state::inheritRetainedLimitations)
            tasks.removeFirst()
            task.expand().asReversed().forEach(tasks::addFirst)
            return true
        }

        private fun flushSet(task: PipelineTask.FlushSet): Boolean {
            val stage = task.stage
            state.inheritRetainedLimitations(stage.right)
            val rows =
                when (val merged = identityRows.flushSet(stage)) {
                    is Refinement.Refined -> merged.value
                    is Refinement.Rejected -> return identityRejected(merged.failure)
                }
            tasks.removeFirst()
            val values =
                rows.map { PipelineTask.Symbol(it, stage.next) } +
                    stage.right.failures.map(PipelineTask::Failure) +
                    stage.right.omissions.map(PipelineTask::Omission) +
                    stage.right.walkObservations.map(PipelineTask::WalkObservation) +
                    stage.right.referenceObservations.map(PipelineTask::ReferenceObservation) +
                    stage.right.discoveryObservations.map { PipelineTask.DiscoveryObservation(it) } +
                    stage.right.relationObservations.map(PipelineTask::RelationObservation)
            values.asReversed().forEach(tasks::addFirst)
            return true
        }

        private fun flushDistinct(task: PipelineTask.FlushDistinct): Boolean {
            val rows = identityRows.flushDistinct(task.stage)
            tasks.removeFirst()
            rows.asReversed().forEach { tasks.addFirst(PipelineTask.Symbol(it, task.stage.next)) }
            return true
        }

        private fun set(task: PipelineTask.Symbol, stage: ExactQueryStage.Set): Boolean {
            when (val accepted = identityRows.acceptSet(stage, task.value)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return identityRejected(accepted.failure)
            }
            tasks.removeFirst()
            return true
        }

        private fun contractViolation(): Boolean {
            state.contractViolation = true
            return false
        }

        private fun identityRejected(failure: QueryIdentityRowFailure): Boolean {
            rejection = QueryExecutionResult.Rejected(failure.executionRejection())
            return false
        }

        private fun emitValuePath(path: QueryImpactPath): Boolean =
            when (
                val admitted =
                    admitValuePathOutput(presentation, { emit(path.retainedBytes) { valuePaths += path } }) {
                        valuePaths += path
                        tasks.removeFirst()
                    }
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> {
                    rejection = QueryExecutionResult.Rejected(admitted.failure)
                    false
                }
            }

        private fun emit(bytes: Long, append: () -> Unit): Boolean {
            if (!state.consumeOutput(bytes)) {
                if (bytes > request.budget.returnedBytes.value) terminal = QueryTerminalReason.OUTPUT_ITEM_TOO_LARGE
                return false
            }
            append()
            tasks.removeFirst()
            return true
        }

        private suspend fun candidate(task: PipelineTask.Candidate): Boolean {
            if (!state.consumeUnit()) return false
            val values = stages.refine(task.value, discoveryDeclarationKinds(request.plan), state)
            tasks.removeFirst()
            values.asReversed().forEach { tasks.addFirst(PipelineTask.Symbol(it, task.stage)) }
            return true
        }

        private suspend fun symbol(task: PipelineTask.Symbol): Boolean =
            when (val stage = task.stage) {
                is ExactQueryStage.Trace -> advanceTrace(task, stage, tasks)
                is ExactQueryStage.ProjectBinding -> contractViolation()
                is ExactQueryStage.Emit -> stages.emitSymbol(task, stage, state, tasks, ::emit) { symbols += it }
                is ExactQueryStage.Distinct -> {
                    when (val accepted = identityRows.acceptDistinct(stage, task.value)) {
                        is Refinement.Refined -> Unit
                        is Refinement.Rejected -> return identityRejected(accepted.failure)
                    }
                    tasks.removeFirst()
                    true
                }
                is ExactQueryStage.Where -> stages.where(task, stage, state, tasks)
                is ExactQueryStage.Concat -> {
                    tasks.removeFirst()
                    tasks.addFirst(task.copy(stage = stage.next))
                    true
                }
                is ExactQueryStage.Set -> set(task, stage)
                is ExactQueryStage.Join -> joinStage.enter(task, stage)
                is ExactQueryStage.Related -> {
                    tasks.removeFirst()
                    tasks.addFirst(PipelineTask.Related(task.value, stage, null))
                    true
                }
                is ExactQueryStage.Walk -> {
                    tasks.removeFirst()
                    tasks.addFirst(PipelineTask.Walk(task.value, stage, null))
                    true
                }
            }

        private suspend fun related(task: PipelineTask.Related): Boolean =
            when (val result = relationStage.read(task, state)) {
                QueryRelationStageResult.NotStarted -> false
                QueryRelationStageResult.ContractRejected -> contractViolation()
                is QueryRelationStageResult.Read -> {
                    tasks.removeFirst()
                    result.nextTasks(task).asReversed().forEach(tasks::addFirst)
                    true
                }
            }

        private suspend fun walk(task: PipelineTask.Walk): Boolean =
            when (val result = walkStage.read(task, state)) {
                QueryWalkStageResult.NotStarted -> false
                QueryWalkStageResult.ContractRejected -> contractViolation()
                is QueryWalkStageResult.Read -> {
                    tasks.removeFirst()
                    result.nextTasks(task).asReversed().forEach(tasks::addFirst)
                    true
                }
            }

        private fun finish(): QueryExecutionResult {
            if (state.contractViolation)
                return QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION)
            val result =
                QueryPageFacts(
                        symbols,
                        occurrences,
                        joinStage.bindingRows,
                        completedFailures,
                        completedOmissions,
                        completedWalkObservations,
                        referenceObservations,
                        discoveryTasks.discoveryObservations,
                        relationObservations,
                        valuePaths,
                    )
                    .result(request.plan, emittedBefore.value, impactTasks.rowsState())
            val pageCount = symbols.size + occurrences.size + joinStage.bindingRows.size + valuePaths.size
            return when (result) {
                is Refinement.Rejected -> QueryExecutionResult.ImpactRejected(result.failure)
                is Refinement.Refined ->
                    state.completePage(result.value, tasks, emittedBefore, pageCount, ::continuation)
            }
        }

        private fun continuation(emittedCount: QueryCount): QueryContinuationState {
            val reason = terminal
            if (reason != null) return QueryContinuationState.Terminal(reason)
            if (tasks.isEmpty()) return QueryContinuationState.Terminal(QueryTerminalReason.UPSTREAM_INCOMPLETE)
            if (!progressed) return QueryContinuationState.Terminal(QueryTerminalReason.NO_PROGRESS)
            return checkpoint(emittedCount).admitContinuation(request.budget.checkpointBytes, checkpointObservation)
        }

        private fun checkpoint(emittedCount: QueryCount): PipelineCheckpoint {
            val accounted =
                when (val current = seed) {
                    PipelineSeed.Unmeasured -> PipelineSeed.Accounted.create(request.plan)
                    is PipelineSeed.Accounted -> current
                }
            seed = accounted
            return PipelineCheckpoint(
                accounted,
                request.lease,
                tasks.toList(),
                identityRows.snapshot(),
                joinStage.snapshot(),
                state.limitations.filterTo(linkedSetOf()) {
                    it !in pageLimits &&
                        !(request.plan.preservesOriginalInvestigation() &&
                            it == QueryLimitation.ROW_SELECTION_INCOMPLETE) &&
                        !(request.plan is AdmittedQueryPlan.Impact &&
                            it == QueryLimitation.IMPACT_COVERAGE_UNPROVEN &&
                            impactTasks.rowsState() == QueryImpactRowsState.Pending)
                } + state.upstreamLimitations,
                emittedCount,
                if (request.plan is AdmittedQueryPlan.Impact) impactTasks.snapshot() else null,
            )
        }
    }
}
