package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.QueryBindingRow
import io.github.amichne.kast.query.contract.QueryDiscoveryObservation
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryOccurrence
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryRelationOmission
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QueryWalkObservation
import io.github.amichne.kast.relation.contract.RelationReferenceOccurrence

/** Completed facts remain attached to their row domain and independent coverage witnesses. */
internal data class QueryPageFacts(
    val symbols: List<QuerySymbol>,
    val occurrences: List<QueryOccurrence>,
    val bindingRows: List<QueryBindingRow>,
    val failures: List<QueryItemFailure>,
    val omissions: List<QueryRelationOmission>,
    val walks: List<QueryWalkObservation>,
    val references: List<RelationReferenceOccurrence>,
    val discoveries: List<QueryDiscoveryObservation>,
) {
    fun result(plan: AdmittedQueryPlan): QueryResult =
        QueryResult(
            when (plan.outputSyntax()) {
                QueryOutputSyntax.Occurrences -> QueryRows.Occurrences.of(occurrences)
                QueryOutputSyntax.BindingRows -> QueryRows.Bindings.of(bindingRows, plan.bindingMode())
                else -> QueryRows.Symbols.of(symbols)
            },
            failures.toList(),
            omissions.toList(),
            walks.toList(),
            referenceObservations = references.toList(),
            discoveryObservations = java.util.Collections.unmodifiableList(discoveries.toList()),
        )
}
