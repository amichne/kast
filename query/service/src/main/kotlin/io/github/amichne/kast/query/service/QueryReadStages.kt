package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryDiscoverySyntax
import io.github.amichne.kast.query.contract.QueryExecutionRejection
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryMatch
import io.github.amichne.kast.query.contract.QueryPredicate
import io.github.amichne.kast.query.contract.QuerySymbol
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

internal class QueryReadStages(
    private val discovery: io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations,
    private val exact: SymbolExactOperations,
    private val source: SourceReadOperations,
) {
    suspend fun discover(
        syntax: QueryDiscoverySyntax,
        state: QueryExecutionState,
    ): DiscoveryExecution {
        val selections =
            linkedMapOf<
                io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate,
                SymbolDiscoverySelection,
            >()
        var progress: DiscoveryExecution = DiscoveryExecution.NotStarted
        for (kind in discoveryKinds(syntax)) {
            val remainingResults = state.remainingResultCapacity(selections.size) ?: return progress
            val childBudget = state.discoveryBudget(remainingResults) ?: return progress
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
            progress = DiscoveryExecution.Discovered(selections.values.toList())
        }
        return progress
    }

    suspend fun refine(
        candidate: SymbolDiscoverySelection,
        discovery: QueryDiscoverySyntax?,
        state: QueryExecutionState,
    ): List<QuerySymbol> = buildList {
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

    suspend fun revalidate(
        selector: io.github.amichne.kast.symbol.contract.SymbolSelector,
        state: QueryExecutionState,
    ): List<QuerySymbol> = buildList {
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

    suspend fun where(
        symbol: QuerySymbol,
        predicate: QueryPredicate,
        state: QueryExecutionState,
    ): List<QuerySymbol> =
        when (predicate) {
            is QueryPredicate.Visibility ->
                buildList {
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

internal sealed interface DiscoveryExecution {
    data object NotStarted : DiscoveryExecution

    data class Discovered(val values: List<SymbolDiscoverySelection>) : DiscoveryExecution

    data class Rejected(val result: QueryExecutionResult.Rejected) : DiscoveryExecution
}
