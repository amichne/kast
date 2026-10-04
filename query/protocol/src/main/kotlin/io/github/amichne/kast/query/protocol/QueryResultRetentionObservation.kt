package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.query.contract.QueryRetainedResultFailure

/** Finite effect receipts contain no source, paths, handles, or arbitrary reason strings. */
enum class QueryResultRetentionScope {
    PRESENTED,
    PENDING_IMPACT,
    ORIGINAL_INVESTIGATION,
}

enum class QueryResultRetentionIssue {
    ISSUED,
    UNAVAILABLE,
    CAPACITY_EXCEEDED,
}

sealed interface QueryResultRetentionEvidence {
    data class CaptureStarted(val scope: QueryResultRetentionScope) : QueryResultRetentionEvidence

    data class Captured(val scope: QueryResultRetentionScope) : QueryResultRetentionEvidence

    data class CaptureRejected(val cause: QueryRetainedResultFailure) : QueryResultRetentionEvidence

    data class Issuance(val outcome: QueryResultRetentionIssue) : QueryResultRetentionEvidence
}

fun interface QueryResultRetentionObservation {
    fun observe(evidence: QueryResultRetentionEvidence)

    companion object {
        val None = QueryResultRetentionObservation {}
    }
}
