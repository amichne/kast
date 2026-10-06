package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryRunRejection

/** Publishes one owned page and releases its claim on every unprepared exit. */
internal class QueryPagePublication(
    private val state: QueryStateStore,
    private val publication: QueryExecutionPublication,
) {
    suspend fun execute(
        claim: QueryExecutionClaim,
        compute: suspend () -> QueryPublishedPage,
    ): QueryPublishedPage {
        var prepared = false
        return try {
            val page = compute()
            if (page is OperationOutcome.Rejected) page
            else
                when (val published = publication.prepare(state, claim, page)) {
                    QueryExecutionPublicationResult.COMMITTED -> page
                    QueryExecutionPublicationResult.PREPARED -> {
                        prepared = true
                        page
                    }
                    is QueryExecutionPublicationResult.Rejected ->
                        OperationOutcome.Rejected(QueryRunRejection.ExecutionRejected(published.failure.rejection()))
                }
        } finally {
            if (!prepared) state.releasePublication(claim)
        }
    }
}
