package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.query.contract.QueryRetainedResultFailure

/** Finite effect receipts contain no source, paths, handles, or arbitrary reason strings. */
enum class QueryResultRetentionIssue {
    ISSUED,
    UNAVAILABLE,
    CAPACITY_EXCEEDED,
}

sealed interface QueryResultRetentionEvidence {
    data object CaptureStarted : QueryResultRetentionEvidence

    data object Captured : QueryResultRetentionEvidence

    data class CaptureRejected(val cause: QueryRetainedResultFailure) : QueryResultRetentionEvidence

    data class Issuance(val outcome: QueryResultRetentionIssue) : QueryResultRetentionEvidence
}

fun interface QueryResultRetentionObservation {
    fun observe(evidence: QueryResultRetentionEvidence)

    companion object {
        val None = QueryResultRetentionObservation {}
    }
}
