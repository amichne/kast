package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A supported next action; a zero-output checkpoint requires an explicit allowance change. */
@Serializable
enum class ReadResumeActionDocument {
    @SerialName("resume") RESUME,
    @SerialName("increase_execution_budget") INCREASE_EXECUTION_BUDGET,
}

/** Qualified completion is closed evidence, carried only by the qualification owner. */
@Serializable
sealed interface QueryQualifiedProgressDocument {
    @Serializable
    @SerialName("resumable")
    data class Resumable(
        val checkpoint: QueryCheckpointDocument,
        @SerialName("next_action") val nextAction: ReadResumeActionDocument,
    ) : QueryQualifiedProgressDocument

    @Serializable
    @SerialName("terminal_incomplete")
    data class TerminalIncomplete(val reason: QueryTerminalReasonDocument) : QueryQualifiedProgressDocument
}

/** Retained output permits draining proven facts; it cannot establish upstream resumability. */
@Serializable
sealed interface QueryCheckpointDocument {
    val token: QueryExecutionContinuation

    @Serializable
    @SerialName("upstream")
    data class Upstream(override val token: QueryExecutionContinuation.Pipeline) : QueryCheckpointDocument

    @Serializable
    @SerialName("retained_output")
    data class RetainedOutput(
        override val token: QueryExecutionContinuation.Output,
        val upstream: QueryPreparedCoverageDocument,
    ) : QueryCheckpointDocument
}

/** Immutable projection of the original canonical outcome retained with an encoded suffix. */
@Serializable
sealed interface QueryPreparedCoverageDocument {
    @Serializable @SerialName("complete") data object Complete : QueryPreparedCoverageDocument

    @Serializable @SerialName("resumable") data object Resumable : QueryPreparedCoverageDocument

    @Serializable
    @SerialName("terminal_incomplete")
    data class TerminalIncomplete(val reason: QueryTerminalReasonDocument) : QueryPreparedCoverageDocument
}

/** Compatibility projections are derived; no result payload may independently supply these fields. */
val QueryQualifiedProgressDocument.continuationToken: QueryExecutionContinuation?
    get() =
        when (this) {
            is QueryQualifiedProgressDocument.Resumable -> checkpoint.token
            is QueryQualifiedProgressDocument.TerminalIncomplete -> null
        }

val QueryQualifiedProgressDocument.terminalReason: QueryTerminalReasonDocument?
    get() =
        when (this) {
            is QueryQualifiedProgressDocument.Resumable -> null
            is QueryQualifiedProgressDocument.TerminalIncomplete -> reason
        }
