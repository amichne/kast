package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Stop-specific facts live on their finite case; an invalid state always retains its exact rejection. */
@Serializable
sealed interface QueryInvocationOutcome {
    val kind: QueryInvocationStop
    val failure: QueryRunRejection?
        get() = null

    @Serializable
    @SerialName("COMPLETED")
    data object Completed : QueryInvocationOutcome {
        override val kind
            get() = QueryInvocationStop.COMPLETED
    }

    @Serializable
    @SerialName("TERMINAL_INCOMPLETE")
    data object TerminalIncomplete : QueryInvocationOutcome {
        override val kind
            get() = QueryInvocationStop.TERMINAL_INCOMPLETE
    }

    @Serializable
    @SerialName("BUDGET_INCREASE_REQUIRED")
    data object BudgetIncreaseRequired : QueryInvocationOutcome {
        override val kind
            get() = QueryInvocationStop.BUDGET_INCREASE_REQUIRED
    }

    @Serializable
    @SerialName("INVALID_STATE")
    data class InvalidState(override val failure: QueryRunRejection) : QueryInvocationOutcome {
        override val kind
            get() = QueryInvocationStop.INVALID_STATE
    }

    @Serializable
    @SerialName("NON_ADVANCING")
    data class NonAdvancing(override val failure: QueryRunRejection) : QueryInvocationOutcome {
        override val kind
            get() = QueryInvocationStop.NON_ADVANCING
    }

    @Serializable
    @SerialName("CANCELLED")
    data object Cancelled : QueryInvocationOutcome {
        override val kind
            get() = QueryInvocationStop.CANCELLED
    }

    @Serializable
    @SerialName("TIME_LIMIT")
    data object TimeLimit : QueryInvocationOutcome {
        override val kind
            get() = QueryInvocationStop.TIME_LIMIT
    }

    @Serializable
    @SerialName("WORK_LIMIT")
    data object WorkLimit : QueryInvocationOutcome {
        override val kind
            get() = QueryInvocationStop.WORK_LIMIT
    }

    @Serializable
    @SerialName("RETAINED_BYTES_LIMIT")
    data object RetainedBytesLimit : QueryInvocationOutcome {
        override val kind
            get() = QueryInvocationStop.RETAINED_BYTES_LIMIT
    }

    @Serializable
    @SerialName("RETENTION_FAILED")
    data class RetentionFailed(
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        override val failure: QueryRunRejection? = null
    ) : QueryInvocationOutcome {
        override val kind
            get() = QueryInvocationStop.RETENTION_FAILED
    }

    companion object {
        internal fun from(kind: QueryInvocationStop, failure: QueryRunRejection?): QueryInvocationOutcome =
            when (kind) {
                QueryInvocationStop.COMPLETED -> Completed
                QueryInvocationStop.TERMINAL_INCOMPLETE -> TerminalIncomplete
                QueryInvocationStop.BUDGET_INCREASE_REQUIRED -> BudgetIncreaseRequired
                QueryInvocationStop.INVALID_STATE -> InvalidState(requireNotNull(failure))
                QueryInvocationStop.NON_ADVANCING ->
                    NonAdvancing(
                        failure
                            ?: QueryRunRejection.ExecutionRejected(
                                QueryExecutionRejectionDocument.NON_ADVANCING_CONTINUATION
                            )
                    )
                QueryInvocationStop.CANCELLED -> Cancelled
                QueryInvocationStop.TIME_LIMIT -> TimeLimit
                QueryInvocationStop.WORK_LIMIT -> WorkLimit
                QueryInvocationStop.RETAINED_BYTES_LIMIT -> RetainedBytesLimit
                QueryInvocationStop.RETENTION_FAILED -> RetentionFailed(failure)
            }
    }
}
