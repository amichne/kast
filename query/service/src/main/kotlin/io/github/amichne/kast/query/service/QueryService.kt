package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionRejection
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryRelationOmission
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QuerySymbolField
import io.github.amichne.kast.query.contract.QuerySymbolSource
import io.github.amichne.kast.query.contract.QueryTerminalReason
import io.github.amichne.kast.query.contract.QueryWalkCoverage
import io.github.amichne.kast.query.contract.QueryWalkObservation
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.SymbolExactOperations
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalOperations

/** Monotonic clock isolated at the evaluator effect boundary. */
fun interface QueryNanoClock {
    fun now(): Long
}

private object SystemQueryNanoClock : QueryNanoClock {
    override fun now(): Long = System.nanoTime()
}

/** In-process evaluator for the closed compositional exact-symbol query algebra. */
class QueryService(
    discovery: io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations,
    exact: SymbolExactOperations,
    source: SourceReadOperations,
    relations: RelationOperations,
    traversal: TraversalOperations,
    private val traversalCeiling: TraversalBudget,
    private val clock: QueryNanoClock = SystemQueryNanoClock,
) : QueryOperations {
    private val stages = QueryReadStages(discovery, exact, source)
    private val relationStage = QueryRelationStage(relations)
    private val walkStage = QueryWalkStage(traversal, traversalCeiling)

    override suspend fun run(request: QueryExecutionRequest): QueryExecutionResult {
        val checkpoint = request.checkpoint
        if (checkpoint != null && checkpoint !is PipelineCheckpoint) {
            return QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION)
        }
        if (request.plan.exceedsTraversalDepth(traversalCeiling.depth)) {
            return QueryExecutionResult.Rejected(QueryExecutionRejection.BUDGET_REJECTED)
        }
        return Execution(request, checkpoint).run()
    }

    /** The single evaluator keeps task order, page accounting, and continuation construction in one owner. */
    @Suppress("LargeClass")
    private inner class Execution(private val request: QueryExecutionRequest, checkpoint: PipelineCheckpoint?) {
        private val state = QueryExecutionState(request, clock)
        private val tasks = ArrayDeque(checkpoint?.tasks ?: initialTasks(request.plan))
        private val identityRows = QueryIdentityRows(checkpoint?.identityRows.orEmpty())
        private val joinStage = QueryJoinStage(request, state, tasks, checkpoint?.joinState)
        private val symbols = mutableListOf<QuerySymbol>()
        private val completedFailures = mutableListOf<QueryItemFailure>()
        private val completedOmissions = mutableListOf<QueryRelationOmission>()
        private val completedWalkObservations = mutableListOf<QueryWalkObservation>()
        private var progressed = false
        private var terminal: QueryTerminalReason? = null
        private var rejection: QueryExecutionResult.Rejected? = null

        init {
            state.limitations += checkpoint?.limitations.orEmpty()
            state.upstreamLimitations += checkpoint?.limitations.orEmpty()
            (request.plan as? io.github.amichne.kast.query.contract.AdmittedQueryPlan.Retained)
                ?.source
                ?.let(state::inheritRetainedLimitations)
        }

        suspend fun run(): QueryExecutionResult {
            while (tasks.isNotEmpty()) {
                if (!executeNext()) break
            }
            return rejection ?: finish()
        }

        private suspend fun executeNext(): Boolean {
            if (
                symbols.size +
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
            return terminal == null && joinStage.terminal == null && rejection == null
        }

        private suspend fun advance(task: PipelineTask): Boolean =
            when (task) {
                is PipelineTask.Failure -> emit(task.value.projectedUtf8Size()) { completedFailures += task.value }
                is PipelineTask.Omission -> emit(task.value.projectedUtf8Size()) { completedOmissions += task.value }
                is PipelineTask.WalkObservation ->
                    emit(task.value.projectedUtf8Size()) { completedWalkObservations += task.value }
                is PipelineTask.Occurrence -> emit(task.value.projectedUtf8Size()) { symbols += task.value }
                is PipelineTask.WalkRecord -> emit(task.value.projectedUtf8Size()) { symbols += task.value }
                is PipelineTask.Candidate -> candidate(task)
                is PipelineTask.Symbol -> symbol(task)
                is PipelineTask.Related -> related(task)
                is PipelineTask.Walk -> walk(task)
                is PipelineTask.Feed -> feed(task)
                is PipelineTask.FlushSet -> flushSet(task)
                is PipelineTask.FlushDistinct -> flushDistinct(task)
                is PipelineTask.FlushBind ->
                    joinStage.flushBind(task, completedFailures, completedOmissions, completedWalkObservations)
                is PipelineTask.JoinEvidence -> joinStage.emitRightEvidence(task)
                is PipelineTask.Join -> joinStage.advance(task)
                is PipelineTask.Discover -> {
                    when (val result = stages.discover(task.syntax, state)) {
                        DiscoveryExecution.NotStarted -> false
                        is DiscoveryExecution.Rejected -> {
                            rejection = result.result
                            true
                        }
                        is DiscoveryExecution.Discovered -> {
                            tasks.removeFirst()
                            result.values.asReversed().forEach { tasks.addFirst(PipelineTask.Candidate(it, task.next)) }
                            true
                        }
                    }
                }
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

        private fun feed(task: PipelineTask.Feed): Boolean {
            (task.stage.input as? io.github.amichne.kast.query.contract.QueryCompositionInput.Retained)
                ?.result
                ?.let(state::inheritRetainedLimitations)
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
                    is Refinement.Rejected -> {
                        state.contractViolation = true
                        return false
                    }
                }
            tasks.removeFirst()
            val values =
                rows.map { PipelineTask.Symbol(it, stage.next) } +
                    stage.right.failures.map(PipelineTask::Failure) +
                    stage.right.omissions.map(PipelineTask::Omission) +
                    stage.right.walkObservations.map(PipelineTask::WalkObservation)
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
            when (identityRows.acceptSet(stage, task.value)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> {
                    state.contractViolation = true
                    return false
                }
            }
            tasks.removeFirst()
            return true
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
            val values = stages.refine(task.value, discoverySyntax(request.plan), state)
            tasks.removeFirst()
            values.asReversed().forEach { tasks.addFirst(PipelineTask.Symbol(it, task.stage)) }
            return true
        }

        private suspend fun symbol(task: PipelineTask.Symbol): Boolean =
            when (val stage = task.stage) {
                is ExactQueryStage.Emit -> emitSymbol(task, stage)
                is ExactQueryStage.Distinct -> {
                    when (identityRows.acceptDistinct(stage, task.value)) {
                        is Refinement.Refined -> Unit
                        is Refinement.Rejected -> {
                            state.contractViolation = true
                            return false
                        }
                    }
                    tasks.removeFirst()
                    true
                }
                is ExactQueryStage.Where -> where(task, stage)
                is ExactQueryStage.Concat -> {
                    tasks.removeFirst()
                    tasks.addFirst(task.copy(stage = stage.next))
                    true
                }
                is ExactQueryStage.Set -> set(task, stage)
                is ExactQueryStage.Bind -> joinStage.bind(task, stage)
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

        private suspend fun where(task: PipelineTask.Symbol, stage: ExactQueryStage.Where): Boolean =
            when (val predicate = stage.predicate) {
                is io.github.amichne.kast.query.contract.QueryPredicate.Primitive -> {
                    tasks.removeFirst()
                    if (stages.matchesPrimitive(task.value, predicate)) tasks.addFirst(task.copy(stage = stage.next))
                    true
                }
                is io.github.amichne.kast.query.contract.QueryPredicate.Visibility ->
                    when (val admitted = state.sourceResources()) {
                        is Refinement.Rejected -> false
                        is Refinement.Refined -> {
                            val values = stages.whereVisibility(task.value, predicate, state, admitted.value)
                            tasks.removeFirst()
                            values.asReversed().forEach { tasks.addFirst(PipelineTask.Symbol(it, stage.next)) }
                            true
                        }
                    }
            }

        private suspend fun emitSymbol(task: PipelineTask.Symbol, stage: ExactQueryStage.Emit): Boolean {
            val expanded = task.expandOutput(stage.output)
            if (expanded != null) {
                tasks.removeFirst()
                expanded.asReversed().forEach(tasks::addFirst)
                return true
            }
            val output =
                stage.output as? QueryOutputSyntax.Symbols
                    ?: run {
                        state.contractViolation = true
                        return false
                    }
            if (QuerySymbolField.SOURCE !in output.fields.values || task.value.source !is QuerySymbolSource.Pending)
                return emit(task.value.projectedUtf8Size()) { symbols += task.value }
            val resources =
                when (val admitted = state.sourceResources()) {
                    is Refinement.Rejected -> return false
                    is Refinement.Refined -> admitted.value
                }
            tasks.removeFirst()
            tasks.addFirst(task.copy(value = stages.sourceWindow(task.value, state, resources)))
            if (state.contractViolation)
                rejection = QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION)
            return true
        }

        private suspend fun related(task: PipelineTask.Related): Boolean {
            return when (val result = relationStage.read(task, state)) {
                QueryRelationStageResult.NotStarted -> false
                QueryRelationStageResult.ContractRejected -> {
                    state.contractViolation = true
                    false
                }
                is QueryRelationStageResult.Read -> {
                    tasks.removeFirst()
                    result.nextTasks(task).asReversed().forEach(tasks::addFirst)
                    true
                }
            }
        }

        private suspend fun walk(task: PipelineTask.Walk): Boolean =
            when (val result = walkStage.read(task, state)) {
                QueryWalkStageResult.NotStarted -> false
                QueryWalkStageResult.ContractRejected -> {
                    state.contractViolation = true
                    false
                }
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
                QueryResult(
                    when (request.plan.outputSyntax()) {
                        QueryOutputSyntax.BindingRows -> QueryRows.Bindings.of(joinStage.bindingRows)
                        else -> QueryRows.Symbols.of(symbols)
                    },
                    completedFailures.toList(),
                    completedOmissions.toList(),
                    completedWalkObservations.toList(),
                )
            val count = (symbols.size + joinStage.bindingRows.size).queryCount()
            if (completedWithoutMissingEvidence())
                return QueryExecutionResult.Complete(result, QueryCoverage.Complete(count))
            if (state.limitations.isEmpty()) state.limit(QueryLimitation.WORK_LIMIT_REACHED)
            val coverage =
                when (val admitted = QueryCoverage.Qualified.create(count, state.limitations)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected ->
                        return QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION)
                }
            return QueryExecutionResult.Qualified(result, coverage, continuation())
        }

        private fun completedWithoutMissingEvidence(): Boolean {
            if (tasks.isNotEmpty() || state.upstreamLimitations.isNotEmpty()) return false
            if (completedFailures.isNotEmpty() || completedOmissions.isNotEmpty()) return false
            if (completedWalkObservations.any { it.coverage !is QueryWalkCoverage.Complete }) return false
            // A clock read after the final successful effect cannot make completed work incomplete.
            return state.limitations.isEmpty() || state.limitations == setOf(QueryLimitation.TIME_LIMIT_REACHED)
        }

        private fun continuation(): QueryContinuationState {
            val reason = terminal ?: joinStage.terminal
            if (reason != null) return QueryContinuationState.Terminal(reason)
            if (tasks.isEmpty()) return QueryContinuationState.Terminal(QueryTerminalReason.UPSTREAM_INCOMPLETE)
            if (!progressed) return QueryContinuationState.Terminal(QueryTerminalReason.NO_PROGRESS)
            val next =
                PipelineCheckpoint(
                    plan = request.plan,
                    lease = request.lease,
                    tasks = tasks.toList(),
                    identityRows = identityRows.snapshot(),
                    joinState = joinStage.snapshot(),
                    limitations =
                        state.limitations.filterTo(linkedSetOf()) { it !in pageLimits } + state.upstreamLimitations,
                )
            return if (next.retainedBytes > request.budget.checkpointBytes.value)
                QueryContinuationState.Terminal(QueryTerminalReason.CHECKPOINT_CAPACITY_EXCEEDED)
            else QueryContinuationState.Resumable(next)
        }
    }
}
