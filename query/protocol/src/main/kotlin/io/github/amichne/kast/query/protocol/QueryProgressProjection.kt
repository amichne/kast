package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryTerminalReason

internal fun projectQueryProgress(
    request: QueryRunRequest,
    continuation: QueryContinuationState,
    itemCount: Int,
    checkpoints: QueryCheckpointStore,
): QueryQualifiedProgressDocument =
    when (continuation) {
        is QueryContinuationState.Resumable ->
            when (val issued = checkpoints.issue(request, continuation.checkpoint)) {
                is QueryCheckpointIssuance.Issued ->
                    QueryQualifiedProgressDocument.Resumable(
                        QueryCheckpointDocument.Upstream(issued.token),
                        if (itemCount == 0) ReadResumeActionDocument.INCREASE_EXECUTION_BUDGET
                        else ReadResumeActionDocument.RESUME,
                    )
                QueryCheckpointIssuance.CapacityExceeded ->
                    QueryQualifiedProgressDocument.TerminalIncomplete(
                        QueryTerminalReasonDocument.CHECKPOINT_CAPACITY_EXCEEDED
                    )
            }
        is QueryContinuationState.Terminal ->
            QueryQualifiedProgressDocument.TerminalIncomplete(continuation.reason.document())
    }

private fun QueryTerminalReason.document() =
    when (this) {
        QueryTerminalReason.UPSTREAM_INCOMPLETE -> QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE
        QueryTerminalReason.OUTPUT_ITEM_TOO_LARGE -> QueryTerminalReasonDocument.OUTPUT_ITEM_TOO_LARGE
        QueryTerminalReason.CHECKPOINT_CAPACITY_EXCEEDED -> QueryTerminalReasonDocument.CHECKPOINT_CAPACITY_EXCEEDED
        QueryTerminalReason.NO_PROGRESS -> QueryTerminalReasonDocument.NO_PROGRESS
    }
