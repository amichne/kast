package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
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
import io.github.amichne.kast.source.contract.SourceEntity
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
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest

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
        return when (val plan = request.plan) {
            is AdmittedQueryPlan.Candidates -> {
                val candidates = when (val discovered = discover(plan.source, state)) {
                    is DiscoveryExecution.Discovered -> discovered.values
                    is DiscoveryExecution.Rejected -> return discovered.result
                }
                executeCandidate(candidates, plan.stage, state, plan.source)
            }
            is AdmittedQueryPlan.Symbols -> {
                val candidates = when (val discovered = discover(plan.source, state)) {
                    is DiscoveryExecution.Discovered -> discovered.values
                    is DiscoveryExecution.Rejected -> return discovered.result
                }
                executeExact(distinct(refine(candidates, plan.source, state), state), plan.stage, state)
            }
            is AdmittedQueryPlan.CandidateReferences ->
                executeCandidate(plan.source.values, plan.stage, state, null)
            is AdmittedQueryPlan.ExactReferences ->
                executeExact(revalidate(plan.source.values, state), plan.stage, state)
        }
    }

    private suspend fun discover(
        syntax: QueryDiscoverySyntax,
        state: QueryExecutionState,
    ): DiscoveryExecution {
        val selections = linkedMapOf<
            io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate,
            SymbolDiscoverySelection,
        >()
        for (kind in discoveryKinds(syntax)) {
            val remainingResults = state.remainingResultCapacity(selections.size)
                ?: return DiscoveryExecution.Discovered(selections.values.toList())
            val childBudget = state.discoveryBudget(remainingResults)
                ?: return DiscoveryExecution.Discovered(selections.values.toList())
            val request = SymbolDiscoveryRequest(
                scope = SymbolSearchScopeRequest(
                    state.request.lease,
                    SymbolSearchScope.Workspace(
                        SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                        SymbolGeneratedSourcePolicy.EXCLUDE,
                        SymbolLibraryPolicy.EXCLUDE,
                    ),
                ),
                target = when (val match = syntax.match) {
                    QueryMatch.All -> SymbolDiscoveryTarget.All(kind)
                    is QueryMatch.Name -> SymbolDiscoveryTarget.Name(
                        kind,
                        match.pattern,
                        match.policy,
                    )
                },
                budget = childBudget,
                constraints = constraints(syntax, kind),
            )
            val outcome = when (val result = discovery.discover(request)) {
                is SymbolDiscoveryResult.Discovered -> result.outcome
                is SymbolDiscoveryResult.Rejected -> return DiscoveryExecution.Rejected(
                    QueryExecutionResult.Rejected(
                        QueryExecutionRejection.DISCOVERY_REJECTED,
                        result.reason,
                    ),
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
                    is Refinement.Refined -> selections.putIfAbsent(
                        selected.value.candidate,
                        selected.value,
                    )
                    is Refinement.Rejected -> state.contractViolation = true
                }
            }
        }
        return DiscoveryExecution.Discovered(selections.values.toList())
    }

    private suspend fun executeCandidate(
        input: List<SymbolDiscoverySelection>,
        stage: CandidateQueryStage,
        state: QueryExecutionState,
        discovery: QueryDiscoverySyntax?,
    ): QueryExecutionResult = when (stage) {
        is CandidateQueryStage.Distinct -> executeCandidate(
            input.distinctBy { it.candidate },
            stage.next,
            state,
            discovery,
        )
        is CandidateQueryStage.Inspect -> executeExact(
            refine(input, discovery, state),
            stage.next,
            state,
        )
        is CandidateQueryStage.Emit -> finish(
            QueryResultSet.Candidates(state.boundResults(input).map(::QueryCandidate)),
            state,
        )
    }

    private suspend fun executeExact(
        input: List<QuerySymbol>,
        stage: ExactQueryStage,
        state: QueryExecutionState,
    ): QueryExecutionResult = when (stage) {
        is ExactQueryStage.Distinct -> executeExact(distinct(input, state), stage.next, state)
        is ExactQueryStage.Where -> executeExact(
            where(input, stage.predicate, state),
            stage.next,
            state,
        )
        is ExactQueryStage.Related -> executeExact(
            related(input, stage.meaning, state),
            stage.next,
            state,
        )
        is ExactQueryStage.Emit -> finish(
            QueryResultSet.Symbols(
                state.boundResults(input).map { symbol ->
                    symbol.copy(connections = state.boundConnections(symbol.connections))
                },
            ),
            state,
        )
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
                    if (result.reason ==
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
    ): List<QuerySymbol> = when (predicate) {
        is QueryPredicate.Visibility -> buildList {
            for (symbol in input) {
                if (!state.consumeUnit()) break
                when (val result = source.read(visibilityRequest(symbol.selector, predicate, state))) {
                    is SourceReadResult.Complete -> if (
                        result.entities.any { entity ->
                            entity is SourceEntity.Declaration &&
                                entity.visibility in predicate.values.values
                        }
                    ) {
                        add(symbol)
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

    private suspend fun related(
        input: List<QuerySymbol>,
        meaning: RelationMeaning,
        state: QueryExecutionState,
    ): List<QuerySymbol> {
        val output = mutableListOf<QuerySymbol>()
        symbolLoop@ for (symbol in input) {
            var continuation: io.github.amichne.kast.relation.contract.RelationContinuation? = null
            while (true) {
                val remainingResults = state.remainingResultCapacity(output.size)
                if (remainingResults == null) {
                    state.limit(QueryLimitation.RELATION_INCOMPLETE)
                    break@symbolLoop
                }
                val budget = state.relationBudget(remainingResults)
                if (budget == null) {
                    state.limit(QueryLimitation.RELATION_INCOMPLETE)
                    break@symbolLoop
                }
                val relationRequest = if (continuation == null) {
                    RelationRequest.start(symbol.selector, meaning, budget)
                } else {
                    when (
                        val resumed = RelationRequest.resume(
                            symbol.selector,
                            meaning,
                            budget,
                            continuation,
                        )
                    ) {
                        is Refinement.Refined -> resumed.value
                        is Refinement.Rejected -> {
                            state.contractViolation = true
                            break@symbolLoop
                        }
                    }
                }
                when (val result = relations.read(relationRequest)) {
                    is RelationReadResult.Complete -> {
                        state.consume(result.batch.examinedWorkUnits.value, result.batch.encodedBytes.value)
                        output += result.batch.facts.map { fact ->
                            fact.toQuerySymbol(state.boundConnections(symbol.connections), state)
                        }
                        state.observeTime()
                        break
                    }
                    is RelationReadResult.Qualified -> {
                        state.consume(result.batch.examinedWorkUnits.value, result.batch.encodedBytes.value)
                        output += result.batch.facts.map { fact ->
                            fact.toQuerySymbol(state.boundConnections(symbol.connections), state)
                        }
                        state.observeTime()
                        when (val coverage = result.coverage) {
                            is RelationIncompleteCoverage.Resumable -> {
                                state.relationPageLimited(coverage.limitations)
                                continuation = coverage.continuation
                            }
                            is RelationIncompleteCoverage.TerminalIncomplete -> {
                                state.relationTerminallyLimited(coverage.limitations)
                                break
                            }
                        }
                    }
                    is RelationReadResult.Rejected -> {
                        state.failure(QueryItemFailure.Relation(symbol.selector, meaning, result.reason))
                        state.limit(QueryLimitation.RELATION_INCOMPLETE)
                        state.observeTime()
                        break
                    }
                }
            }
        }
        return output
    }

    private fun finish(
        items: QueryResultSet,
        state: QueryExecutionState,
    ): QueryExecutionResult {
        state.observeTime()
        if (state.contractViolation) {
            return QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION)
        }
        val failures = state.boundedFailures()
        val boundedItems = when (items) {
            is QueryResultSet.Candidates -> QueryResultSet.Candidates(
                state.boundOutput(items.values, QueryCandidate::projectedUtf8Size),
            )
            is QueryResultSet.Symbols -> QueryResultSet.Symbols(
                state.boundOutput(items.values, QuerySymbol::projectedUtf8Size),
            )
        }
        val result = QueryResult(boundedItems, failures)
        val count = when (boundedItems) {
            is QueryResultSet.Candidates -> boundedItems.values.size
            is QueryResultSet.Symbols -> boundedItems.values.size
        }.queryCount()
        return if (state.limitations.isEmpty()) {
            QueryExecutionResult.Complete(result, QueryCoverage.Complete(count))
        } else {
            val coverage = when (
                val created = QueryCoverage.Qualified.create(count, state.limitations)
            ) {
                is Refinement.Refined -> created.value
                is Refinement.Rejected -> return QueryExecutionResult.Rejected(
                    QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION,
                )
            }
            QueryExecutionResult.Qualified(result, coverage)
        }
    }
}

private sealed interface DiscoveryExecution {
    data class Discovered(val values: List<SymbolDiscoverySelection>) : DiscoveryExecution
    data class Rejected(val result: QueryExecutionResult.Rejected) : DiscoveryExecution
}
