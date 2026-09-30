package io.github.amichne.kast.query.protocol

/** Pure protocol callers publish immediately; hosted callers retain the attempt until final host admission. */
fun interface QueryExecutionPublication {
    fun prepare(
        store: QueryStateStore,
        claim: QueryExecutionClaim,
        page: QueryPublishedPage,
    ): QueryExecutionPublicationResult

    data object Immediate : QueryExecutionPublication {
        override fun prepare(store: QueryStateStore, claim: QueryExecutionClaim, page: QueryPublishedPage) =
            when (val committed = store.commitPublication(claim, page)) {
                QueryPublicationCommit.Committed -> QueryExecutionPublicationResult.COMMITTED
                is QueryPublicationCommit.Rejected -> QueryExecutionPublicationResult.Rejected(committed.failure)
            }
    }
}

sealed interface QueryExecutionPublicationResult {
    data object COMMITTED : QueryExecutionPublicationResult

    data object PREPARED : QueryExecutionPublicationResult

    data class Rejected(val failure: QueryPublicationFailure) : QueryExecutionPublicationResult
}

internal fun QueryPublicationFailure.rejection():
    io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument =
    when (this) {
        QueryPublicationFailure.OWNER_RETIRED ->
            io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument.CONTINUATION_OWNER_RETIRED
        QueryPublicationFailure.CLAIM_UNAVAILABLE ->
            io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument.CONTINUATION_CLAIM_UNAVAILABLE
        QueryPublicationFailure.EXPIRED ->
            io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument.CONTINUATION_EXPIRED
        QueryPublicationFailure.DEPENDENCY_UNAVAILABLE ->
            io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument.CONTINUATION_DEPENDENCY_UNAVAILABLE
        QueryPublicationFailure.PUBLISHED_PAGE_MISMATCH ->
            io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument.PUBLISHED_PAGE_MISMATCH
        QueryPublicationFailure.NON_ADVANCING_SUCCESSOR ->
            io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument.NON_ADVANCING_CONTINUATION
    }
