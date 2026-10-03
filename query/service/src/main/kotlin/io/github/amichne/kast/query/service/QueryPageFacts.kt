package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.QueryBindingRow
import io.github.amichne.kast.query.contract.QueryDiscoveryObservation
import io.github.amichne.kast.query.contract.QueryImpactExecutionFailure
import io.github.amichne.kast.query.contract.QueryImpactRowIdentityFailure
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryOccurrence
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryRelationOmission
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
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
    val relations: List<io.github.amichne.kast.query.contract.QueryRelationObservation>,
    val valuePaths: List<io.github.amichne.kast.query.contract.QueryImpactPath>,
) {
    fun result(
        plan: AdmittedQueryPlan,
        emittedBefore: Int,
        investigated: QueryImpactRowsState = QueryImpactRowsState.Pending,
    ): Refinement<QueryResult, QueryImpactExecutionFailure> {
        val rows =
            when (plan.outputSyntax()) {
                is QueryOutputSyntax.ImpactWitness ->
                    return Refinement.Rejected(QueryImpactExecutionFailure.PresentationOnly)
                QueryOutputSyntax.Occurrences -> QueryRows.Occurrences.of(occurrences)
                QueryOutputSyntax.BindingRows -> QueryRows.Bindings.of(bindingRows, plan.bindingMode())
                QueryOutputSyntax.ValuePaths ->
                    when (val selected = valuePathRows(plan, emittedBefore, investigated)) {
                        is Refinement.Rejected -> return selected
                        is Refinement.Refined -> selected.value
                    }
                is QueryOutputSyntax.Symbols,
                QueryOutputSyntax.TraversalRecords -> QueryRows.Symbols.of(symbols)
            }
        return Refinement.Refined(
            QueryResult(
                rows,
                failures.toList(),
                omissions.toList(),
                walks.toList(),
                referenceObservations = references.toList(),
                discoveryObservations = java.util.Collections.unmodifiableList(discoveries.toList()),
                relationObservations = java.util.Collections.unmodifiableList(relations.toList()),
            )
        )
    }

    private fun valuePathRows(
        plan: AdmittedQueryPlan,
        emittedBefore: Int,
        investigated: QueryImpactRowsState,
    ): Refinement<QueryRows.ValuePaths, QueryImpactExecutionFailure> {
        val source =
            when (investigated) {
                is QueryImpactRowsState.Rejected -> return Refinement.Rejected(investigated.failure)
                is QueryImpactRowsState.Ready -> investigated.rows
                QueryImpactRowsState.Pending -> null
            }
                ?: ((plan as? AdmittedQueryPlan.Retained)?.source as? QueryRetainedResult.ValuePaths)?.rows
                ?: return Refinement.Refined(QueryRows.ValuePaths.of(valuePaths))
        val selected =
            when (val subset = source.selectRows((emittedBefore until emittedBefore + valuePaths.size).toList())) {
                is Refinement.Refined -> subset.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(QueryImpactExecutionFailure.Selection(subset.failure))
            }
        return if (selected.values != valuePaths)
            Refinement.Rejected(
                QueryImpactExecutionFailure.RowIdentity(QueryImpactRowIdentityFailure.CHANGED_RETAINED_ROWS)
            )
        else Refinement.Refined(selected)
    }
}
