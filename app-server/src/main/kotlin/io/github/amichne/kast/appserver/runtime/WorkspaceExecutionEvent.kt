package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.BrokerWorkspaceId
import io.github.amichne.kast.appserver.core.BrokerCallId
import io.github.amichne.kast.appserver.core.BrokerThreadId
import io.github.amichne.kast.appserver.core.BrokerTurnId
import io.github.amichne.kast.appserver.protocol.codex.InvocationCertainty
import io.github.amichne.kast.appserver.protocol.codex.ProtocolRouting
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import kotlin.time.Duration

internal data class WorkspaceExecutionIdentity(
    val workspace: BrokerWorkspaceId,
    val connection: ClientConnectionId,
    val thread: BrokerThreadId,
    val turn: BrokerTurnId,
    val call: BrokerCallId,
)

internal enum class WorkspaceExecutionFailure(val certainty: InvocationCertainty) {
    WORKSPACE_QUEUE_CAPACITY_EXCEEDED(InvocationCertainty.KNOWN),
    WORKSPACE_QUEUE_TIMED_OUT(InvocationCertainty.KNOWN),
    CANCELLED_BEFORE_EXECUTION(InvocationCertainty.KNOWN),
    CANCELLED_AFTER_RECOVERY(InvocationCertainty.KNOWN),
    WORKSPACE_RECOVERY_REQUIRED(InvocationCertainty.KNOWN),
    WORKSPACE_INTERACTION_TIMED_OUT(InvocationCertainty.UNCERTAIN),
    WORKSPACE_OUTCOME_UNCERTAIN(InvocationCertainty.UNCERTAIN),
}

internal sealed interface WorkspaceExecutionResult {
    data class Completed(val routing: ProtocolRouting) : WorkspaceExecutionResult

    data class Rejected(val failure: WorkspaceExecutionFailure) : WorkspaceExecutionResult
}

internal enum class WorkspaceExecutionStage {
    ADMISSION,
    QUEUE,
    EXECUTION,
}

internal enum class WorkspaceExecutionOutcome {
    ACCEPTED,
    STARTED,
    COMPLETED,
    REJECTED,
    CANCELLED,
    TIMED_OUT,
    UNCERTAIN,
}

internal data class WorkspaceExecutionEvent(
    val request: WorkspaceExecutionIdentity,
    val stage: WorkspaceExecutionStage,
    val outcome: WorkspaceExecutionOutcome,
    val elapsed: Duration,
    val queued: Duration,
    val interactionLimit: ElapsedTimeLimitMillis,
    val failure: WorkspaceExecutionFailure? = null,
)

internal fun WorkspaceExecutionResult.certainty(): InvocationCertainty =
    when (this) {
        is WorkspaceExecutionResult.Completed ->
            if (routing is ProtocolRouting.ReplyUpstream) routing.certainty else InvocationCertainty.UNCERTAIN
        is WorkspaceExecutionResult.Rejected -> failure.certainty
    }

internal fun WorkspaceExecutionResult.outcome(): WorkspaceExecutionOutcome =
    when (this) {
        is WorkspaceExecutionResult.Completed ->
            if (certainty() == InvocationCertainty.UNCERTAIN) WorkspaceExecutionOutcome.UNCERTAIN
            else WorkspaceExecutionOutcome.COMPLETED
        is WorkspaceExecutionResult.Rejected ->
            when (failure) {
                WorkspaceExecutionFailure.WORKSPACE_INTERACTION_TIMED_OUT,
                WorkspaceExecutionFailure.WORKSPACE_QUEUE_TIMED_OUT -> WorkspaceExecutionOutcome.TIMED_OUT
                WorkspaceExecutionFailure.CANCELLED_BEFORE_EXECUTION -> WorkspaceExecutionOutcome.CANCELLED
                else ->
                    if (failure.certainty == InvocationCertainty.UNCERTAIN) WorkspaceExecutionOutcome.UNCERTAIN
                    else WorkspaceExecutionOutcome.REJECTED
            }
    }
