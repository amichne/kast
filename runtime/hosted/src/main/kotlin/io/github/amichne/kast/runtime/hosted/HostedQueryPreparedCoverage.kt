package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryPreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument

/** Derived from the canonical outcome whose detached suffix is retained before publication. */
internal fun HostedQueryOutcome.preparedCoverage(): QueryPreparedCoverageDocument =
    when (this) {
        is OperationOutcome.Complete -> QueryPreparedCoverageDocument.Complete
        is OperationOutcome.Qualified ->
            when (val progress = qualification.progress) {
                is QueryQualifiedProgressDocument.TerminalIncomplete ->
                    QueryPreparedCoverageDocument.TerminalIncomplete(progress.reason)
                is QueryQualifiedProgressDocument.Resumable ->
                    when (val checkpoint = progress.checkpoint) {
                        is QueryCheckpointDocument.Upstream -> QueryPreparedCoverageDocument.Resumable
                        is QueryCheckpointDocument.RetainedOutput -> checkpoint.upstream
                    }
            }
        is OperationOutcome.Rejected -> error("Rejected queries cannot establish retained output coverage")
    }
