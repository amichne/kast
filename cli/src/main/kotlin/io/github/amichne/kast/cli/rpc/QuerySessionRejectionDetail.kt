package io.github.amichne.kast.cli.rpc

import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocumentFailure
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Session policy explains only local authority. The semantic owner decides source freshness. */
@Serializable
internal sealed interface QuerySessionRejectionDetail {
    @Serializable
    @SerialName("NEW_RUN_REQUIRES_NEW_SESSION")
    data class NewRun
    private constructor(
        @Required
        @SerialName("next_action")
        val nextAction: QuerySessionRecoveryAction = QuerySessionRecoveryAction.START_NEW_QUERY_SESSION
    ) : QuerySessionRejectionDetail {
        companion object {
            val value = NewRun()
        }
    }

    @Serializable
    @SerialName("SOURCE_READ_REQUIRES_NEW_SESSION")
    data class SourceRead
    private constructor(
        @Required
        @SerialName("next_action")
        val nextAction: QuerySessionRecoveryAction = QuerySessionRecoveryAction.START_NEW_QUERY_SESSION
    ) : QuerySessionRejectionDetail {
        companion object {
            val value = SourceRead()
        }
    }

    @Serializable
    @SerialName("RESULT_NOT_ISSUED_BY_PRECEDING_REPLY")
    data class ResultNotIssued(
        @SerialName("expected_result") val expected: QueryResultReference?,
        @SerialName("requested_result") val requested: QueryResultReference,
    ) : QuerySessionRejectionDetail {
        @Required
        @SerialName("next_action")
        val nextAction: QuerySessionRecoveryAction = QuerySessionRecoveryAction.START_NEW_QUERY_SESSION
    }

    @Serializable
    @SerialName("CONTINUATION_NOT_ISSUED_BY_PRECEDING_REPLY")
    data class ContinuationNotIssued(
        @SerialName("expected_continuation") val expected: QueryExecutionContinuation.Output?,
        @SerialName("requested_continuation") val requested: QueryExecutionContinuation,
    ) : QuerySessionRejectionDetail {
        @Required
        @SerialName("next_action")
        val nextAction: QuerySessionRecoveryAction = QuerySessionRecoveryAction.START_NEW_QUERY_SESSION
    }

    @Serializable
    @SerialName("SOURCE_ANCHOR_REJECTED")
    data class SourceAnchor(val cause: SourceReadAnchorDocumentFailure) : QuerySessionRejectionDetail {
        @Required
        @SerialName("next_action")
        val nextAction: SourceAnchorRecoveryAction = SourceAnchorRecoveryAction.USE_ISSUED_CANDIDATE_SELECTOR
    }
}

@Serializable
internal enum class QuerySessionRecoveryAction {
    START_NEW_QUERY_SESSION
}

@Serializable
internal enum class SourceAnchorRecoveryAction {
    USE_ISSUED_CANDIDATE_SELECTOR
}
