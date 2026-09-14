package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.*
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.CandidateQueryStage
import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryCandidate
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
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationMeaning
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
        val state = QueryExecutionState(request, clock)
        val retained = request.checkpoint
        if (retained != null && retained !is PipelineCheckpoint) {
            return QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION)
        }
        val checkpoint = retained as PipelineCheckpoint?
        val tasks = ArrayDeque(checkpoint?.tasks ?: initialTasks(request.plan))
        val seenCandidates = checkpoint?.candidateDistinct?.mapValues { it.value.toMutableSet() }?.toMutableMap() ?: mutableMapOf()
        val seenSymbols = checkpoint?.symbolDistinct?.mapValues { it.value.toMutableSet() }?.toMutableMap() ?: mutableMapOf()
        state.limitations += checkpoint?.limitations.orEmpty()
        val candidates = mutableListOf<QueryCandidate>()
        val symbols = mutableListOf<QuerySymbol>()
        var progressed = false
        var terminal: QueryTerminalReason? = null
        while (tasks.isNotEmpty()) {
            if (candidates.size + symbols.size + state.failureCount >= request.budget.resources.resultLimit.value) {
                state.limit(QueryLimitation.RESULT_LIMIT_REACHED)
                break
            }
            val task = tasks.first()
            val needsWork = task !is PipelineTask.Candidate && task !is PipelineTask.Symbol
            if (!state.canContinue(needsWork)) break
            when (task) {
                is PipelineTask.Discover -> {
                    when (val result = discover(task.syntax, state)) {
                        is DiscoveryExecution.Rejected -> return result.result
                        is DiscoveryExecution.Discovered -> {
                            tasks.removeFirst()
                            result.values.asReversed().forEach { tasks.addFirst(PipelineTask.Candidate(it, task.next)) }
                        }
                    }
                }
                is PipelineTask.Revalidate -> {
                    val values = revalidate(listOf(task.selector), state)
                    tasks.removeFirst()
                    values.asReversed().forEach { tasks.addFirst(PipelineTask.Symbol(it, task.next)) }
                }
                is PipelineTask.Candidate -> when (val stage = task.stage) {
                    is CandidateQueryStage.Emit -> {
                        val candidate = QueryCandidate(task.value)
                        val bytes = candidate.projectedUtf8Size()
                        if (!state.consumeOutput(bytes)) {
                            if (bytes > request.budget.returnedBytes.value) terminal = QueryTerminalReason.OUTPUT_ITEM_TOO_LARGE
                            break
                        }
                        candidates += candidate
                        tasks.removeFirst()
                    }
                    is CandidateQueryStage.Distinct -> {
                        tasks.removeFirst()
                        if (seenCandidates.getOrPut(stage) { mutableSetOf() }.add(task.value.candidate)) {
                            tasks.addFirst(task.copy(stage = stage.next))
                        }
                    }
                    is CandidateQueryStage.Inspect -> {
                        if (!state.canContinue(true)) break
                        val values = refine(listOf(task.value), discoverySyntax(request.plan), state)
                        tasks.removeFirst()
                        values.asReversed().forEach { tasks.addFirst(PipelineTask.Symbol(it, stage.next)) }
                    }
                }
                is PipelineTask.Symbol -> when (val stage = task.stage) {
                    is ExactQueryStage.Emit -> {
                        val bytes = task.value.projectedUtf8Size()
                        if (!state.consumeOutput(bytes)) {
                            if (bytes > request.budget.returnedBytes.value) terminal = QueryTerminalReason.OUTPUT_ITEM_TOO_LARGE
                            break
                        }
                        symbols += task.value
                        tasks.removeFirst()
                    }
                    is ExactQueryStage.Distinct -> {
                        tasks.removeFirst()
                        if (seenSymbols.getOrPut(stage) { mutableSetOf() }.add(task.value.selector.fingerprint)) {
                            tasks.addFirst(task.copy(stage = stage.next))
                        }
                    }
                    is ExactQueryStage.Where -> {
                        if (!state.canContinue(true)) break
                        val values = where(listOf(task.value), stage.predicate, state)
                        tasks.removeFirst()
                        values.asReversed().forEach { tasks.addFirst(PipelineTask.Symbol(it, stage.next)) }
                    }
                    is ExactQueryStage.Related -> {
                        tasks.removeFirst()
                        tasks.addFirst(PipelineTask.Related(task.value, stage, null))
                    }
                }
                is PipelineTask.Related -> {
                    val childBudget = state.relationBudget(request.budget.resources.resultLimit.value) ?: break
                    val child = if (task.cursor == null) {
                        RelationRequest.start(task.value.selector, task.stage.meaning, childBudget,
                            io.github.amichne.kast.relation.contract.RelationSearchBoundary.WORKSPACE_EXPANSION)
                    } else {
                        when (val resumed = RelationRequest.resume(task.value.selector, task.stage.meaning, childBudget,
                            task.cursor, io.github.amichne.kast.relation.contract.RelationSearchBoundary.WORKSPACE_EXPANSION)) {
                            is Refinement.Refined -> resumed.value
                            is Refinement.Rejected -> return QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION)
                        }
                    }
                    val result = relations.read(child)
                    tasks.removeFirst()
                    val facts = when (result) {
                        is RelationReadResult.Complete -> {
                            state.consume(result.batch.examinedWorkUnits.value.coerceAtLeast(1L), result.batch.encodedBytes.value)
                            result.batch.facts
                        }
                        is RelationReadResult.Qualified -> {
                            state.consume(result.batch.examinedWorkUnits.value.coerceAtLeast(1L), result.batch.encodedBytes.value)
                            when (val coverage = result.coverage) {
                                is RelationIncompleteCoverage.Resumable -> {
                                    state.relationPageLimited(coverage.limitations)
                                    if (coverage.continuation == task.cursor) {
                                        state.limit(QueryLimitation.RELATION_INCOMPLETE)
                                        terminal = QueryTerminalReason.NO_PROGRESS
                                    } else tasks.addFirst(task.copy(cursor = coverage.continuation))
                                }
                                is RelationIncompleteCoverage.TerminalIncomplete -> state.relationTerminallyLimited(coverage.limitations)
                            }
                            result.batch.facts
                        }
                        is RelationReadResult.Rejected -> {
                            state.failure(QueryItemFailure.Relation(task.value.selector, task.stage.meaning, result.reason))
                            state.limit(QueryLimitation.RELATION_INCOMPLETE)
                            emptyList()
                        }
                    }
                    facts.asReversed().forEach { tasks.addFirst(PipelineTask.Symbol(it.toQuerySymbol(task.value.connections, state), task.stage.next)) }
                    state.observeTime()
                }
            }
            progressed = true
            if (terminal != null) break
        }
        if (state.contractViolation) return QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION)
        val failures = state.boundedFailures()
        val items = if (isCandidateOutput(request.plan)) QueryResultSet.Candidates(candidates) else QueryResultSet.Symbols(symbols)
        val result = QueryResult(items, failures)
        val count = (candidates.size + symbols.size).queryCount()
        if (tasks.isEmpty() && state.limitations.isEmpty()) return QueryExecutionResult.Complete(result, QueryCoverage.Complete(count))
        if (state.limitations.isEmpty()) state.limit(QueryLimitation.WORK_LIMIT_REACHED)
        val continuation = when {
            terminal != null -> QueryContinuationState.Terminal(terminal)
            tasks.isEmpty() -> QueryContinuationState.Terminal(QueryTerminalReason.UPSTREAM_INCOMPLETE)
            !progressed -> QueryContinuationState.Terminal(QueryTerminalReason.NO_PROGRESS)
            else -> {
                val next = PipelineCheckpoint(request.plan, request.lease, tasks.toList(),
                    seenCandidates.mapValues { it.value.toSet() }, seenSymbols.mapValues { it.value.toSet() },
                    state.limitations.filterTo(linkedSetOf()) { it !in pageLimits })
                if (next.retainedBytes > MAX_CHECKPOINT_BYTES) QueryContinuationState.Terminal(QueryTerminalReason.CHECKPOINT_CAPACITY_EXCEEDED)
                else QueryContinuationState.Resumable(next)
            }
        }
        val coverage = when (val admitted = QueryCoverage.Qualified.create(count, state.limitations)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION)
        }
        return QueryExecutionResult.Qualified(result, coverage, continuation)
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
            state.consume(batch.examinedWorkUnits.value, batch.encodedBytes.value)
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
