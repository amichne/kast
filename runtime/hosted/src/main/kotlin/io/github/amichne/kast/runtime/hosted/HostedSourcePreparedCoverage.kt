package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.SourceCheckpointDocument
import io.github.amichne.kast.protocol.contract.SourcePreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.SourceQualifiedProgressDocument

internal fun HostedSourceOutcome.preparedCoverage(): SourcePreparedCoverageDocument =
    when (this) {
        is OperationOutcome.Complete -> SourcePreparedCoverageDocument.Complete
        is OperationOutcome.Qualified ->
            when (val progress = qualification.progress) {
                is SourceQualifiedProgressDocument.TerminalIncomplete ->
                    SourcePreparedCoverageDocument.TerminalIncomplete(progress.reason)
                is SourceQualifiedProgressDocument.Resumable ->
                    when (val checkpoint = progress.checkpoint) {
                        is SourceCheckpointDocument.Upstream -> SourcePreparedCoverageDocument.Resumable
                        is SourceCheckpointDocument.RetainedOutput -> checkpoint.upstream
                    }
            }
        is OperationOutcome.Rejected -> error("Rejected source reads have no retained output")
    }
