package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryExecutionRejection
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryTextDiscoverySyntax
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryProgress
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy

/** Indexed ingress delegates to the existing discovery capability and exact query evaluator. */
internal class QueryTextDiscoveryStage(private val discovery: SymbolDiscoveryOperations) {
    /** Uses indexed word discovery while retaining one verified exemplar for each declaration owner. */
    suspend fun discover(syntax: QueryTextDiscoverySyntax, state: QueryExecutionState): DiscoveryExecution {
        val budget = state.textDiscoveryBudget() ?: return DiscoveryExecution.NotStarted
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
                target = SymbolDiscoveryTarget.TextDeclarations(syntax.word),
                budget = budget,
                constraints = constraints(syntax),
            )
        val outcome =
            when (val result = discovery.discover(request)) {
                is SymbolDiscoveryResult.Discovered -> result.outcome
                is SymbolDiscoveryResult.Rejected ->
                    return DiscoveryExecution.Rejected(
                        QueryExecutionResult.Rejected(QueryExecutionRejection.DISCOVERY_REJECTED, result.reason)
                    )
            }
        state.observeTime()
        val batch = outcome.batch()
        state.consume(batch.examinedWorkUnits.value)
        if (batch.candidates.any { it.textMatch?.word != syntax.word }) {
            return DiscoveryExecution.Rejected(
                QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION)
            )
        }
        observeDiscoveryLimitations(outcome, state)
        val selections =
            batch.candidates.indices.mapNotNull { ordinal ->
                when (val selected = SymbolDiscoverySelection.select(batch, ordinal)) {
                    is Refinement.Refined -> selected.value
                    is Refinement.Rejected -> {
                        state.contractViolation = true
                        null
                    }
                }
            }
        return DiscoveryExecution.Discovered(
            selections,
            outcome.progress,
            io.github.amichne.kast.query.contract.QueryDiscoveryObservation.from(request, outcome),
        )
    }
}

internal fun observeDiscoveryLimitations(outcome: SymbolDiscoveryOutcome, state: QueryExecutionState) {
    if (outcome is SymbolDiscoveryOutcome.Qualified) {
        when (outcome.progress) {
            is SymbolDiscoveryProgress.Resumable -> state.discoveryPageLimited(outcome.qualifications.values)
            SymbolDiscoveryProgress.Exhausted,
            is SymbolDiscoveryProgress.Blocked -> state.discoveryLimited(outcome.qualifications.values)
        }
    }
}
