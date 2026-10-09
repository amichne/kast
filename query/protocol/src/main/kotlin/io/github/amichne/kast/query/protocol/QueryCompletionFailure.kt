package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactRequiredObligationDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphFailureDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionRetentionFailure
import io.github.amichne.kast.protocol.contract.QueryCompletionUnprovenReason
import io.github.amichne.kast.protocol.contract.QueryImpactRequiredObligationsDocument
import io.github.amichne.kast.protocol.contract.QueryInvestigationCompletionFailureDocument
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryValuePathAccountingStatus
import io.github.amichne.kast.query.contract.accountingStatus

internal sealed interface QueryCompletionFailure {
    val reason: QueryCompletionUnprovenReason

    data object Incomplete : QueryCompletionFailure {
        override val reason = QueryCompletionUnprovenReason.INCOMPLETE_EXECUTION
    }

    data class Retention(val failure: QueryCompletionRetentionFailure) : QueryCompletionFailure {
        override val reason = QueryCompletionUnprovenReason.RETENTION_UNAVAILABLE
    }

    data object Item : QueryCompletionFailure {
        override val reason = QueryCompletionUnprovenReason.ITEM_FAILURE
    }

    data object Omitted : QueryCompletionFailure {
        override val reason = QueryCompletionUnprovenReason.OMITTED_EVIDENCE
    }

    data class Investigation(val failure: QueryInvestigationCompletionFailureDocument) : QueryCompletionFailure {
        override val reason = QueryCompletionUnprovenReason.INVESTIGATION_UNPROVEN
    }

    data class Callback(val cause: QueryCallbackGraphFailureDocument) : QueryCompletionFailure {
        override val reason = QueryCompletionUnprovenReason.CALLBACK_GRAPH_UNPROVEN
    }
}

/** Cheap finite failures precede construction of every context-sensitive callback graph. */
internal fun completionProof(execution: QueryExecutionResult): Refinement<Unit, QueryCompletionFailure> =
    when (execution) {
        is QueryExecutionResult.Rejection -> Refinement.Rejected(QueryCompletionFailure.Incomplete)
        is QueryExecutionResult.Qualified ->
            when (val proof = investigationProof(execution.result.rows)) {
                is Refinement.Rejected -> proof
                is Refinement.Refined -> Refinement.Rejected(QueryCompletionFailure.Incomplete)
            }
        is QueryExecutionResult.Complete -> completionProof(execution.result)
    }

internal fun completionProof(result: QueryResult): Refinement<Unit, QueryCompletionFailure> =
    when {
        result.failures.isNotEmpty() -> Refinement.Rejected(QueryCompletionFailure.Item)
        result.omissions.isNotEmpty() -> Refinement.Rejected(QueryCompletionFailure.Omitted)
        else ->
            when (val proof = investigationProof(result.rows)) {
                is Refinement.Rejected -> proof
                is Refinement.Refined -> callbackProof(result.relationObservations, result.walkObservations)
            }
    }

internal fun completionProof(result: QueryRetainedResult): Refinement<Unit, QueryCompletionFailure> =
    when {
        result is QueryRetainedResult.ValuePaths && investigationProof(result.rows) is Refinement.Rejected ->
            investigationProof(result.rows)
        result.coverage is QueryCoverage.Qualified -> Refinement.Rejected(QueryCompletionFailure.Incomplete)
        result.failures.isNotEmpty() -> Refinement.Rejected(QueryCompletionFailure.Item)
        result.omissions.isNotEmpty() -> Refinement.Rejected(QueryCompletionFailure.Omitted)
        else -> callbackProof(result.relationObservations, result.walkObservations)
    }

internal fun QueryCompletionFailure.protocolCompletionCause(): QueryCompletionCauseDocument =
    when (this) {
        QueryCompletionFailure.Incomplete -> QueryCompletionCauseDocument.IncompleteExecution
        is QueryCompletionFailure.Retention -> QueryCompletionCauseDocument.RetentionUnavailable(failure)
        QueryCompletionFailure.Item -> QueryCompletionCauseDocument.ItemFailure
        QueryCompletionFailure.Omitted -> QueryCompletionCauseDocument.OmittedEvidence
        is QueryCompletionFailure.Investigation -> QueryCompletionCauseDocument.InvestigationUnproven(failure)
        is QueryCompletionFailure.Callback -> QueryCompletionCauseDocument.CallbackGraphUnproven(cause)
    }

private fun investigationProof(rows: QueryRows): Refinement<Unit, QueryCompletionFailure> {
    if (rows !is QueryRows.ValuePaths) return Refinement.Refined(Unit)
    val failure =
        when (val status = rows.accountingStatus) {
            QueryValuePathAccountingStatus.Conserved -> return Refinement.Refined(Unit)
            QueryValuePathAccountingStatus.EvidenceOnly -> QueryInvestigationCompletionFailureDocument.MissingOriginal
            is QueryValuePathAccountingStatus.SelectedSubset ->
                QueryInvestigationCompletionFailureDocument.SelectionIncomplete
            is QueryValuePathAccountingStatus.Unresolved ->
                QueryInvestigationCompletionFailureDocument.ObligationsUnresolved(
                    QueryImpactRequiredObligationsDocument.from(
                            status.required
                                .sortedBy { it.ordinal }
                                .map { ImpactRequiredObligationDocument.valueOf(it.name) }
                        )
                        .required()
                )
        }
    return Refinement.Rejected(QueryCompletionFailure.Investigation(failure))
}
