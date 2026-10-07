package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphFailureDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionUnprovenReason
import io.github.amichne.kast.protocol.contract.QueryCompletionUnsupportedReason
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult

internal sealed interface QueryCompletionFailure {
    val reason: QueryCompletionUnprovenReason

    data object Incomplete : QueryCompletionFailure {
        override val reason = QueryCompletionUnprovenReason.INCOMPLETE_EXECUTION
    }

    data object Item : QueryCompletionFailure {
        override val reason = QueryCompletionUnprovenReason.ITEM_FAILURE
    }

    data object Omitted : QueryCompletionFailure {
        override val reason = QueryCompletionUnprovenReason.OMITTED_EVIDENCE
    }

    data class Callback(val cause: QueryCallbackGraphFailureDocument) : QueryCompletionFailure {
        override val reason = QueryCompletionUnprovenReason.CALLBACK_GRAPH_UNPROVEN
    }
}

/** Cheap finite failures precede construction of every context-sensitive callback graph. */
internal fun completionProof(execution: QueryExecutionResult): Refinement<Unit, QueryCompletionFailure> =
    when (execution) {
        is QueryExecutionResult.Rejection,
        is QueryExecutionResult.Qualified -> Refinement.Rejected(QueryCompletionFailure.Incomplete)
        is QueryExecutionResult.Complete -> completionProof(execution.result)
    }

internal fun completionProof(result: QueryResult): Refinement<Unit, QueryCompletionFailure> =
    when {
        result.failures.isNotEmpty() -> Refinement.Rejected(QueryCompletionFailure.Item)
        result.omissions.isNotEmpty() -> Refinement.Rejected(QueryCompletionFailure.Omitted)
        else -> callbackProof(result.relationObservations, result.walkObservations)
    }

internal fun completionProof(result: QueryRetainedResult): Refinement<Unit, QueryCompletionFailure> =
    when {
        result.coverage is QueryCoverage.Qualified -> Refinement.Rejected(QueryCompletionFailure.Incomplete)
        result.failures.isNotEmpty() -> Refinement.Rejected(QueryCompletionFailure.Item)
        result.omissions.isNotEmpty() -> Refinement.Rejected(QueryCompletionFailure.Omitted)
        else -> callbackProof(result.relationObservations, result.walkObservations)
    }

internal enum class PageAdmission {
    PUBLIC,
    AUTOMATIC_INVOCATION,
}

internal fun completionAdmission(
    request: QueryRunRequest.Run,
    admission: PageAdmission,
): Refinement<Unit, QueryRunRejection> {
    val completion = request.completion
    return if (admission == PageAdmission.PUBLIC && completion is QueryCompletionPolicyDocument.CompleteOnly)
        Refinement.Rejected(
            QueryRunRejection.CompletionUnsupported(
                completion.model,
                QueryCompletionUnsupportedReason.AUTOMATIC_EXECUTION_REQUIRED,
            )
        )
    else Refinement.Refined(Unit)
}

internal fun QueryCompletionFailure.protocolCompletionCause():
    io.github.amichne.kast.protocol.contract.QueryCompletionCauseDocument =
    when (this) {
        QueryCompletionFailure.Incomplete ->
            io.github.amichne.kast.protocol.contract.QueryCompletionCauseDocument.IncompleteExecution
        QueryCompletionFailure.Item -> io.github.amichne.kast.protocol.contract.QueryCompletionCauseDocument.ItemFailure
        QueryCompletionFailure.Omitted ->
            io.github.amichne.kast.protocol.contract.QueryCompletionCauseDocument.OmittedEvidence
        is QueryCompletionFailure.Callback ->
            io.github.amichne.kast.protocol.contract.QueryCompletionCauseDocument.CallbackGraphUnproven(cause)
    }
