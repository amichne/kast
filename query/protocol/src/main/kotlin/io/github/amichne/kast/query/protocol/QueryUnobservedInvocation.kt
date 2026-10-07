package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryRunRejection

/** Without an observed row family, an invocation stop is a failure rather than an invented empty result. */
internal fun QueryInvocationTransition.Stopped.unobservedFailure(): QueryRunRejection =
    failure
        ?: QueryRunRejection.ExecutionRejected(
            when (reason) {
                QueryInvocationStop.TIME_LIMIT -> QueryExecutionRejectionDocument.INVOCATION_TIME_LIMIT
                QueryInvocationStop.WORK_LIMIT -> QueryExecutionRejectionDocument.INVOCATION_WORK_LIMIT
                QueryInvocationStop.RETAINED_BYTES_LIMIT ->
                    QueryExecutionRejectionDocument.INVOCATION_RETAINED_BYTES_LIMIT
                QueryInvocationStop.CANCELLED -> QueryExecutionRejectionDocument.INVOCATION_CANCELLED
                QueryInvocationStop.NON_ADVANCING -> QueryExecutionRejectionDocument.NON_ADVANCING_CONTINUATION
                QueryInvocationStop.COMPLETED,
                QueryInvocationStop.TERMINAL_INCOMPLETE,
                QueryInvocationStop.BUDGET_INCREASE_REQUIRED,
                QueryInvocationStop.INVALID_STATE,
                QueryInvocationStop.RETENTION_FAILED -> QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION
            }
        )
