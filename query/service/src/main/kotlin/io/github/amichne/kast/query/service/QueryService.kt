package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryArrivalEvidence
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
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QuerySymbolField
import io.github.amichne.kast.query.contract.QuerySymbolSource
import io.github.amichne.kast.query.contract.QueryTerminalReason
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.SymbolExactOperations

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
    private val clock: QueryNanoClock = SystemQueryNanoClock,
) : QueryOperations {
    private val stages = QueryReadStages(discovery, exact, source)
    private val relationStage = QueryRelationStage(relations)

    override suspend fun run(request: QueryExecutionRequest): QueryExecutionResult {
        val checkpoint = request.checkpoint
        if (checkpoint != null && checkpoint !is PipelineCheckpoint) {
            return QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION)
        }
        return Execution(request, checkpoint).run()
    }

    private inner class Execution(private val request: QueryExecutionRequest, checkpoint: PipelineCheckpoint?) {
        private val state = QueryExecutionState(request, clock)
        private val tasks = ArrayDeque(checkpoint?.tasks ?: initialTasks(request.plan))
        private val identityRows = QueryIdentityRows(checkpoint?.identityRows.orEmpty())
        private val symbols = mutableListOf<QuerySymbol>()
        private val completedFailures = mutableListOf<QueryItemFailure>()
        private val completedOmissions = mutableListOf<QueryRelationOmission>()
        private var progressed = false
        private var terminal: QueryTerminalReason? = null
        private var rejection: QueryExecutionResult.Rejected? = null

        init {
            state.limitations += checkpoint?.limitations.orEmpty()
            state.upstreamLimitations += checkpoint?.limitations.orEmpty()
            request.plan.retainedInputs().forEach(state::inheritRetainedLimitations)
        }

        suspend fun run(): QueryExecutionResult {
            while (tasks.isNotEmpty()) {
                if (!executeNext()) break
            }
            return rejection ?: finish()
        }

        private suspend fun executeNext(): Boolean {
            if (
                symbols.size + completedFailures.size + completedOmissions.size >=
                    request.budget.resources.resultLimit.value
            ) {
                state.limit(QueryLimitation.RESULT_LIMIT_REACHED)
                return false
            }
            val task = tasks.first()
            val needsWork =
                task is PipelineTask.Discover || task is PipelineTask.Revalidate || task is PipelineTask.Related
            if (!state.canContinue(needsWork) || !advance(task)) return false
            state.drainFailures().asReversed().forEach { tasks.addFirst(PipelineTask.Failure(it)) }
            progressed = true
            return terminal == null && rejection == null
        }

        private suspend fun advance(task: PipelineTask): Boolean =
            when (task) {
                is PipelineTask.Failure -> emit(task.value.projectedUtf8Size()) { completedFailures += task.value }
                is PipelineTask.Omission -> emit(task.value.projectedUtf8Size()) { completedOmissions += task.value }
                is PipelineTask.Occurrence -> emit(task.value.projectedUtf8Size()) { symbols += task.value }
                is PipelineTask.Candidate -> candidate(task)
                is PipelineTask.Symbol -> symbol(task)
                is PipelineTask.Related -> related(task)
                is PipelineTask.Feed -> feed(task)
                is PipelineTask.FlushSet -> flushSet(task)
                is PipelineTask.FlushDistinct -> flushDistinct(task)
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
            tasks.removeFirst()
            task.expand().asReversed().forEach(tasks::addFirst)
            return true
        }

        private fun flushSet(task: PipelineTask.FlushSet): Boolean {
            val stage = task.stage
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
                    stage.right.omissions.map(PipelineTask::Omission)
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
                is ExactQueryStage.Related -> {
                    tasks.removeFirst()
                    tasks.addFirst(PipelineTask.Related(task.value, stage, null))
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
            when (val output = stage.output) {
                QueryOutputSyntax.Occurrences -> {
                    tasks.removeFirst()
                    val facts = (task.value.arrival as? QueryArrivalEvidence.Proven)?.facts.orEmpty()
                    facts.asReversed().forEach { fact ->
                        tasks.addFirst(
                            PipelineTask.Occurrence(task.value.copy(arrival = QueryArrivalEvidence.Proven.one(fact)))
                        )
                    }
                    return true
                }
                is QueryOutputSyntax.Symbols -> {
                    if (
                        QuerySymbolField.SOURCE !in output.fields.values ||
                            task.value.source !is QuerySymbolSource.Pending
                    )
                        return emit(task.value.projectedUtf8Size()) { symbols += task.value }
                }
            }
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
                    result.continuation?.let { tasks.addFirst(task.copy(cursor = it)) }
                    result.omissions.asReversed().forEach { omission ->
                        tasks.addFirst(PipelineTask.Omission(omission))
                    }
                    result.symbols.asReversed().forEach { symbol ->
                        tasks.addFirst(PipelineTask.Symbol(symbol, task.stage.next))
                    }
                    true
                }
            }
        }

        private fun finish(): QueryExecutionResult {
            if (state.contractViolation)
                return QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION)
            val result = QueryResult(symbols.toList(), completedFailures.toList(), completedOmissions.toList())
            val count = symbols.size.queryCount()
            if (tasks.isEmpty() && state.limitations.isEmpty())
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

        private fun continuation(): QueryContinuationState {
            val reason = terminal
            if (reason != null) return QueryContinuationState.Terminal(reason)
            if (tasks.isEmpty()) return QueryContinuationState.Terminal(QueryTerminalReason.UPSTREAM_INCOMPLETE)
            if (!progressed) return QueryContinuationState.Terminal(QueryTerminalReason.NO_PROGRESS)
            val next =
                PipelineCheckpoint(
                    plan = request.plan,
                    lease = request.lease,
                    tasks = tasks.toList(),
                    identityRows = identityRows.snapshot(),
                    limitations =
                        state.limitations.filterTo(linkedSetOf()) { it !in pageLimits } + state.upstreamLimitations,
                )
            return if (next.retainedBytes > request.budget.checkpointBytes.value)
                QueryContinuationState.Terminal(QueryTerminalReason.CHECKPOINT_CAPACITY_EXCEEDED)
            else QueryContinuationState.Resumable(next)
        }
    }
}
