package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.CandidateQueryStage
import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryCandidate
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryDiscoverySyntax
import io.github.amichne.kast.query.contract.QueryExecutionRejection
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryMatch
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryPredicate
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryResultSet
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QueryTerminalReason
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.source.contract.SourceDeclarationVisibility
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.source.contract.SourceReadResult
import io.github.amichne.kast.symbol.contract.ExactSymbolRequest
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolExactOperations
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolResolutionRequest
import io.github.amichne.kast.symbol.contract.SymbolResolutionResult
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy

/** Monotonic clock isolated at the evaluator effect boundary. */
fun interface QueryNanoClock {
    fun now(): Long
}

private object SystemQueryNanoClock : QueryNanoClock {
    override fun now(): Long = System.nanoTime()
}

/** In-process evaluator for the closed linear candidate/exact-symbol query algebra. */
class QueryService(
    private val discovery: io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations,
    private val exact: SymbolExactOperations,
    private val source: SourceReadOperations,
    private val relations: RelationOperations,
    private val clock: QueryNanoClock = SystemQueryNanoClock,
) : QueryOperations {
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
        private val seenCandidates =
            checkpoint?.candidateDistinct?.mapValues { it.value.toMutableSet() }?.toMutableMap() ?: mutableMapOf()
        private val seenSymbols =
            checkpoint?.symbolDistinct?.mapValues { it.value.toMutableSet() }?.toMutableMap() ?: mutableMapOf()
        private val candidates = mutableListOf<QueryCandidate>()
        private val symbols = mutableListOf<QuerySymbol>()
        private val completedFailures = mutableListOf<QueryItemFailure>()
        private var progressed = false
        private var terminal: QueryTerminalReason? = null
        private var rejection: QueryExecutionResult.Rejected? = null

        init {
            state.limitations += checkpoint?.limitations.orEmpty()
            state.upstreamLimitations += checkpoint?.limitations.orEmpty()
        }

        suspend fun run(): QueryExecutionResult {
            while (tasks.isNotEmpty()) {
                if (!executeNext()) break
            }
            return rejection ?: finish()
        }

        private suspend fun executeNext(): Boolean {
            if (candidates.size + symbols.size + completedFailures.size >= request.budget.resources.resultLimit.value) {
                state.limit(QueryLimitation.RESULT_LIMIT_REACHED)
                return false
            }
            val task = tasks.first()
            val needsWork =
                task !is PipelineTask.Candidate && task !is PipelineTask.Symbol && task !is PipelineTask.Failure
            if (!state.canContinue(needsWork) || !advance(task)) return false
            state.drainFailures().asReversed().forEach { tasks.addFirst(PipelineTask.Failure(it)) }
            progressed = true
            return terminal == null && rejection == null
        }

        private suspend fun advance(task: PipelineTask): Boolean =
            when (task) {
                is PipelineTask.Failure -> emit(task.value.projectedUtf8Size()) { completedFailures += task.value }
                is PipelineTask.Candidate -> candidate(task)
                is PipelineTask.Symbol -> symbol(task)
                is PipelineTask.Related -> related(task)
                is PipelineTask.Discover -> {
                    when (val result = discover(task.syntax, state)) {
                        is DiscoveryExecution.Rejected -> rejection = result.result
                        is DiscoveryExecution.Discovered -> {
                            tasks.removeFirst()
                            result.values.asReversed().forEach { tasks.addFirst(PipelineTask.Candidate(it, task.next)) }
                        }
                    }
                    true
                }
                is PipelineTask.Revalidate -> {
                    val values = revalidate(listOf(task.selector), state)
                    tasks.removeFirst()
                    values.asReversed().forEach { tasks.addFirst(PipelineTask.Symbol(it, task.next)) }
                    true
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

        private suspend fun candidate(task: PipelineTask.Candidate): Boolean =
            when (val stage = task.stage) {
                is CandidateQueryStage.Emit -> {
                    val value = QueryCandidate(task.value)
                    emit(value.projectedUtf8Size()) { candidates += value }
                }
                is CandidateQueryStage.Distinct -> {
                    tasks.removeFirst()
                    if (seenCandidates.getOrPut(stage) { mutableSetOf() }.add(task.value.candidate))
                        tasks.addFirst(task.copy(stage = stage.next))
                    true
                }
                is CandidateQueryStage.Inspect -> {
                    if (!state.canContinue(true)) false
                    else {
                        val values = refine(listOf(task.value), discoverySyntax(request.plan), state)
                        tasks.removeFirst()
                        values.asReversed().forEach { tasks.addFirst(PipelineTask.Symbol(it, stage.next)) }
                        true
                    }
                }
            }

        private suspend fun symbol(task: PipelineTask.Symbol): Boolean =
            when (val stage = task.stage) {
                is ExactQueryStage.Emit -> emit(task.value.projectedUtf8Size()) { symbols += task.value }
                is ExactQueryStage.Distinct -> {
                    tasks.removeFirst()
                    if (
                        seenSymbols
                            .getOrPut(stage) { mutableSetOf() }
                            .add(io.github.amichne.kast.symbol.contract.CanonicalSymbolId.from(task.value.selector))
                    )
                        tasks.addFirst(task.copy(stage = stage.next))
                    true
                }
                is ExactQueryStage.Where -> {
                    if (!state.canContinue(true)) false
                    else {
                        val values = where(listOf(task.value), stage.predicate, state)
                        tasks.removeFirst()
                        values.asReversed().forEach { tasks.addFirst(PipelineTask.Symbol(it, stage.next)) }
                        true
                    }
                }
                is ExactQueryStage.Related -> {
                    tasks.removeFirst()
                    tasks.addFirst(PipelineTask.Related(task.value, stage, null))
                    true
                }
            }

        private suspend fun related(task: PipelineTask.Related): Boolean {
            val childBudget = state.relationBudget(request.budget.resources.resultLimit.value) ?: return false
            val boundary = io.github.amichne.kast.relation.contract.RelationSearchBoundary.WORKSPACE_EXPANSION
            val child =
                if (task.cursor == null) {
                    RelationRequest.start(
                        selector = task.value.selector,
                        meaning = task.stage.meaning,
                        budget = childBudget,
                        boundary = boundary,
                    )
                } else {
                    when (
                        val resumed =
                            RelationRequest.resume(
                                selector = task.value.selector,
                                meaning = task.stage.meaning,
                                budget = childBudget,
                                continuation = task.cursor,
                                boundary = boundary,
                            )
                    ) {
                        is Refinement.Refined -> resumed.value
                        is Refinement.Rejected -> {
                            state.contractViolation = true
                            return false
                        }
                    }
                }
            val result = relations.read(child)
            tasks.removeFirst()
            val facts = relationFacts(result, task)
            facts.asReversed().forEach { fact ->
                tasks.addFirst(PipelineTask.Symbol(fact.toQuerySymbol(task.value.connections, state), task.stage.next))
            }
            state.observeTime()
            return true
        }

        private fun relationFacts(
            result: RelationReadResult,
            task: PipelineTask.Related,
        ): List<io.github.amichne.kast.relation.contract.RelationFact> =
            when (result) {
                is RelationReadResult.Complete -> {
                    state.consume(result.batch.examinedWorkUnits.value.coerceAtLeast(1L))
                    result.batch.facts
                }
                is RelationReadResult.Qualified -> {
                    state.consume(result.batch.examinedWorkUnits.value.coerceAtLeast(1L))
                    when (val coverage = result.coverage) {
                        is RelationIncompleteCoverage.Resumable -> {
                            state.relationPageLimited(coverage.limitations)
                            tasks.addFirst(task.copy(cursor = coverage.continuation))
                        }
                        is RelationIncompleteCoverage.TerminalIncomplete ->
                            state.relationTerminallyLimited(coverage.limitations)
                    }
                    result.batch.facts
                }
                is RelationReadResult.Rejected -> {
                    state.failure(QueryItemFailure.Relation(task.value.selector, task.stage.meaning, result.reason))
                    state.limit(QueryLimitation.RELATION_INCOMPLETE)
                    emptyList()
                }
            }

        private fun finish(): QueryExecutionResult {
            if (state.contractViolation)
                return QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION)
            val items =
                if (isCandidateOutput(request.plan)) QueryResultSet.Candidates(candidates)
                else QueryResultSet.Symbols(symbols)
            val result = QueryResult(items, completedFailures)
            val count = (candidates.size + symbols.size).queryCount()
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
                    candidateDistinct = seenCandidates.mapValues { it.value.toSet() },
                    symbolDistinct = seenSymbols.mapValues { it.value.toSet() },
                    limitations =
                        state.limitations.filterTo(linkedSetOf()) { it !in pageLimits } + state.upstreamLimitations,
                )
            return if (next.retainedBytes > request.budget.checkpointBytes.value)
                QueryContinuationState.Terminal(QueryTerminalReason.CHECKPOINT_CAPACITY_EXCEEDED)
            else QueryContinuationState.Resumable(next)
        }
    }

    private suspend fun discover(
        syntax: QueryDiscoverySyntax,
        state: QueryExecutionState,
    ): DiscoveryExecution {
        val selections =
            linkedMapOf<
                io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate,
                SymbolDiscoverySelection,
            >()
        for (kind in discoveryKinds(syntax)) {
            val remainingResults =
                state.remainingResultCapacity(selections.size)
                    ?: return DiscoveryExecution.Discovered(selections.values.toList())
            val childBudget =
                state.discoveryBudget(remainingResults)
                    ?: return DiscoveryExecution.Discovered(selections.values.toList())
            val request =
                SymbolDiscoveryRequest(
                    scope =
                        SymbolSearchScopeRequest(
                            state.request.lease,
                            SymbolSearchScope.Workspace(
                                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                                SymbolGeneratedSourcePolicy.EXCLUDE,
                                SymbolLibraryPolicy.EXCLUDE,
                            ),
                        ),
                    target =
                        when (val match = syntax.match) {
                            QueryMatch.All -> SymbolDiscoveryTarget.All(kind)
                            is QueryMatch.Name ->
                                SymbolDiscoveryTarget.Name(
                                    kind,
                                    match.pattern,
                                    match.policy,
                                )
                        },
                    budget = childBudget,
                    constraints = constraints(syntax, kind),
                )
            val outcome =
                when (val result = discovery.discover(request)) {
                    is SymbolDiscoveryResult.Discovered -> result.outcome
                    is SymbolDiscoveryResult.Rejected ->
                        return DiscoveryExecution.Rejected(
                            QueryExecutionResult.Rejected(
                                QueryExecutionRejection.DISCOVERY_REJECTED,
                                result.reason,
                            )
                        )
                }
            state.observeTime()
            val batch = outcome.batch()
            state.consume(batch.examinedWorkUnits.value)
            if (outcome is SymbolDiscoveryOutcome.Qualified) {
                state.discoveryLimited(outcome.qualifications.values)
            }
            batch.candidates.indices.forEach { ordinal ->
                when (val selected = SymbolDiscoverySelection.select(batch, ordinal)) {
                    is Refinement.Refined ->
                        selections.putIfAbsent(
                            selected.value.candidate,
                            selected.value,
                        )
                    is Refinement.Rejected -> state.contractViolation = true
                }
            }
        }
        return DiscoveryExecution.Discovered(selections.values.toList())
    }

    private suspend fun refine(
        candidates: List<SymbolDiscoverySelection>,
        discovery: QueryDiscoverySyntax?,
        state: QueryExecutionState,
    ): List<QuerySymbol> = buildList {
        for (candidate in candidates) {
            if (!state.consumeUnit()) break
            when (val result = exact.resolve(SymbolResolutionRequest(candidate))) {
                is SymbolResolutionResult.Resolved -> {
                    val selector = result.symbol.selector
                    if (discovery == null || selector.kind in discovery.declarationKinds.values) {
                        add(QuerySymbol(SymbolDescription.from(selector), emptyList()))
                    }
                }
                is SymbolResolutionResult.Rejected -> {
                    state.failure(QueryItemFailure.Refinement(candidate, result.reason))
                    state.limit(QueryLimitation.REFINEMENT_INCOMPLETE)
                }
            }
            state.observeTime()
        }
    }

    private suspend fun revalidate(
        selectors: List<io.github.amichne.kast.symbol.contract.SymbolSelector>,
        state: QueryExecutionState,
    ): List<QuerySymbol> = buildList {
        for (selector in selectors) {
            if (!state.consumeUnit()) break
            when (val result = exact.describe(ExactSymbolRequest(selector))) {
                is SymbolDescriptionResult.Described -> add(QuerySymbol(result.description, emptyList()))
                is SymbolDescriptionResult.Rejected -> {
                    state.failure(QueryItemFailure.ExactReference(selector, result.reason))
                    state.limit(QueryLimitation.REFINEMENT_INCOMPLETE)
                    if (
                        result.reason ==
                            io.github.amichne.kast.symbol.contract.SymbolExactRejection.COMPILER_CONTRACT_VIOLATION
                    ) {
                        state.contractViolation = true
                    }
                }
            }
            state.observeTime()
        }
    }

    private suspend fun where(
        input: List<QuerySymbol>,
        predicate: QueryPredicate,
        state: QueryExecutionState,
    ): List<QuerySymbol> =
        when (predicate) {
            is QueryPredicate.Visibility ->
                buildList {
                    for (symbol in input) {
                        if (!state.consumeUnit()) break
                        when (val result = source.read(visibilityRequest(symbol.selector, state))) {
                            is SourceReadResult.Complete ->
                                when (val evidence = SourceDeclarationVisibility.admit(symbol.selector, result)) {
                                    is Refinement.Refined ->
                                        if (evidence.value.visibility in predicate.values.values) add(symbol)
                                    is Refinement.Rejected -> {
                                        state.failure(QueryItemFailure.PredicateUnproven(symbol.selector))
                                        state.limit(QueryLimitation.VISIBILITY_INCOMPLETE)
                                    }
                                }
                            is SourceReadResult.Qualified -> {
                                state.failure(QueryItemFailure.PredicateUnproven(symbol.selector))
                                state.limit(QueryLimitation.VISIBILITY_INCOMPLETE)
                            }
                            is SourceReadResult.Rejected -> {
                                state.failure(QueryItemFailure.Visibility(symbol.selector, result.reason))
                                state.limit(QueryLimitation.VISIBILITY_INCOMPLETE)
                            }
                        }
                        state.observeTime()
                    }
                }
        }
}

private sealed interface DiscoveryExecution {
    data class Discovered(val values: List<SymbolDiscoverySelection>) : DiscoveryExecution

    data class Rejected(val result: QueryExecutionResult.Rejected) : DiscoveryExecution
}
