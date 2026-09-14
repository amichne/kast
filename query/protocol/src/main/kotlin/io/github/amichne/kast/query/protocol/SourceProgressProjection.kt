package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.ProtocolTextFailure
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.protocol.contract.SourceCheckpointDocument
import io.github.amichne.kast.protocol.contract.SourceQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.SourceTerminalReasonDocument
import io.github.amichne.kast.source.contract.SourceReadContinuationState
import io.github.amichne.kast.source.contract.SourceReadLimitation
import io.github.amichne.kast.source.contract.SourceReadQualification

internal fun SourceReadQualification.projectProgress(
    entityCount: Int
): Refinement<SourceQualifiedProgressDocument, ProtocolTextFailure> =
    when (val state = continuation) {
        SourceReadContinuationState.Unavailable ->
            Refinement.Refined(
                SourceQualifiedProgressDocument.TerminalIncomplete(
                    if (limitations == listOf(SourceReadLimitation.TEXT_BYTE_LIMIT_REACHED))
                        SourceTerminalReasonDocument.TEXT_PROJECTION_WITHHELD
                    else SourceTerminalReasonDocument.UPSTREAM_INCOMPLETE
                )
            )
        is SourceReadContinuationState.Available ->
            when (val token = ProtocolText.parse(state.continuation.value)) {
                is Refinement.Rejected -> token
                is Refinement.Refined ->
                    Refinement.Refined(
                        SourceQualifiedProgressDocument.Resumable(
                            SourceCheckpointDocument.Upstream(token.value),
                            if (entityCount == 0) ReadResumeActionDocument.INCREASE_EXECUTION_BUDGET
                            else ReadResumeActionDocument.RESUME,
                        )
                    )
            }
    }
