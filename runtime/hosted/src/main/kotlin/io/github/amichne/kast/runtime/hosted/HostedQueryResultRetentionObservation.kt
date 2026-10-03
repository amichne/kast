package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.query.contract.QueryRetainedResultFailure
import io.github.amichne.kast.query.protocol.QueryResultRetentionEvidence
import io.github.amichne.kast.query.protocol.QueryResultRetentionIssue
import io.github.amichne.kast.query.protocol.QueryResultRetentionObservation
import io.github.amichne.kast.query.protocol.QueryResultRetentionScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase

/** Existing bounded read diagnostics observe exact capture/issuance outcomes, never source or handles. */
internal fun hostedQueryResultRetentionObservation(observation: IntellijReadObservation) =
    QueryResultRetentionObservation { evidence ->
        observation.phase(IntellijReadPhase.RETENTION)
        observation.count(evidence.retentionCounter())
    }

private fun QueryResultRetentionEvidence.retentionCounter(): IntellijReadCounter =
    when (this) {
        is QueryResultRetentionEvidence.CaptureStarted ->
            when (scope) {
                QueryResultRetentionScope.PRESENTED -> IntellijReadCounter.QUERY_RETENTION_PRESENTED_CAPTURE_STARTED
                QueryResultRetentionScope.PENDING_IMPACT ->
                    IntellijReadCounter.QUERY_RETENTION_PENDING_IMPACT_CAPTURE_STARTED
                QueryResultRetentionScope.ORIGINAL_INVESTIGATION ->
                    IntellijReadCounter.QUERY_RETENTION_ORIGINAL_CAPTURE_STARTED
            }
        is QueryResultRetentionEvidence.Captured ->
            when (scope) {
                QueryResultRetentionScope.PRESENTED -> IntellijReadCounter.QUERY_RETENTION_PRESENTED_CAPTURED
                QueryResultRetentionScope.PENDING_IMPACT -> IntellijReadCounter.QUERY_RETENTION_PENDING_IMPACT_CAPTURED
                QueryResultRetentionScope.ORIGINAL_INVESTIGATION ->
                    IntellijReadCounter.QUERY_RETENTION_ORIGINAL_CAPTURED
            }
        is QueryResultRetentionEvidence.CaptureRejected -> cause.retentionCounter()
        is QueryResultRetentionEvidence.Issuance ->
            when (outcome) {
                QueryResultRetentionIssue.ISSUED -> IntellijReadCounter.QUERY_RETENTION_RESULT_ISSUED
                QueryResultRetentionIssue.UNAVAILABLE -> IntellijReadCounter.QUERY_RETENTION_RESULT_UNAVAILABLE
                QueryResultRetentionIssue.CAPACITY_EXCEEDED ->
                    IntellijReadCounter.QUERY_RETENTION_RESULT_CAPACITY_EXCEEDED
            }
    }

private fun QueryRetainedResultFailure.retentionCounter(): IntellijReadCounter =
    when (this) {
        QueryRetainedResultFailure.EXECUTION_REJECTED -> IntellijReadCounter.QUERY_RETENTION_CAPTURE_EXECUTION_REJECTED
        QueryRetainedResultFailure.PRESENTATION_ONLY_ROWS ->
            IntellijReadCounter.QUERY_RETENTION_CAPTURE_PRESENTATION_ONLY_ROWS
        QueryRetainedResultFailure.BASIS_MISMATCH -> IntellijReadCounter.QUERY_RETENTION_CAPTURE_BASIS_MISMATCH
        QueryRetainedResultFailure.INCONSISTENT_COVERAGE ->
            IntellijReadCounter.QUERY_RETENTION_CAPTURE_INCONSISTENT_COVERAGE
        QueryRetainedResultFailure.UNKNOWN_ROW -> IntellijReadCounter.QUERY_RETENTION_CAPTURE_UNKNOWN_ROW
        QueryRetainedResultFailure.DUPLICATE_ROW -> IntellijReadCounter.QUERY_RETENTION_CAPTURE_DUPLICATE_ROW
    }
