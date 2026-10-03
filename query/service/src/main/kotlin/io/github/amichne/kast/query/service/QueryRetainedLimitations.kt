package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryValuePathAccountingStatus
import io.github.amichne.kast.query.contract.QueryWalkCoverage
import io.github.amichne.kast.query.contract.accountingStatus
import io.github.amichne.kast.traversal.contract.TraversalLimitation

internal fun retainedQueryLimitations(result: QueryRetainedResult): List<QueryLimitation> {
    val inherited = (result.coverage as? QueryCoverage.Qualified)?.limitations.orEmpty()
    val failures =
        result.failures.map { failure ->
            when (failure) {
                is QueryItemFailure.Refinement,
                is QueryItemFailure.ExactReference -> QueryLimitation.REFINEMENT_INCOMPLETE
                is QueryItemFailure.Visibility,
                is QueryItemFailure.PredicateUnproven -> QueryLimitation.VISIBILITY_INCOMPLETE
                is QueryItemFailure.Source -> QueryLimitation.SOURCE_INCOMPLETE
                is QueryItemFailure.Relation -> QueryLimitation.RELATION_INCOMPLETE
                is QueryItemFailure.Walk -> QueryLimitation.TRAVERSAL_INCOMPLETE
            }
        }
    val omissions = if (result.omissions.isEmpty()) emptyList() else listOf(QueryLimitation.RELATION_INCOMPLETE)
    val walks =
        if (result.walkObservations.any { it.coverage.isTerminallyIncomplete() })
            listOf(QueryLimitation.TRAVERSAL_INCOMPLETE)
        else emptyList()
    val impacts =
        if (result is QueryRetainedResult.ValuePaths)
            when (result.rows.accountingStatus) {
                QueryValuePathAccountingStatus.Conserved -> emptyList()
                QueryValuePathAccountingStatus.EvidenceOnly,
                is QueryValuePathAccountingStatus.Unresolved -> listOf(QueryLimitation.IMPACT_COVERAGE_UNPROVEN)
                is QueryValuePathAccountingStatus.SelectedSubset -> listOf(QueryLimitation.ROW_SELECTION_INCOMPLETE)
            }
        else emptyList()
    return inherited + failures + omissions + walks + impacts
}

private fun QueryWalkCoverage.isTerminallyIncomplete(): Boolean =
    this is QueryWalkCoverage.TerminalIncomplete ||
        (this is QueryWalkCoverage.Resumable && TraversalLimitation.ONE_HOP_INCOMPLETE in limitations)
