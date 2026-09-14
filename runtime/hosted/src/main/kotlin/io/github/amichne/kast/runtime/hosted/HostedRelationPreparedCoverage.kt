package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.RelationCheckpointDocument
import io.github.amichne.kast.protocol.contract.RelationPreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.RelationReadQualification

/** Fitting changes output delivery, never the coverage proved by upstream relation work. */
internal fun HostedRelationOutcome.preparedRelationCoverage(): RelationPreparedCoverageDocument =
    when (this) {
        is OperationOutcome.Complete -> RelationPreparedCoverageDocument.COMPLETE
        is OperationOutcome.Qualified ->
            when (val progress = qualification) {
                is RelationReadQualification.TerminalIncomplete -> RelationPreparedCoverageDocument.TERMINAL_INCOMPLETE
                is RelationReadQualification.Resumable ->
                    when (val checkpoint = progress.checkpoint) {
                        is RelationCheckpointDocument.Upstream -> RelationPreparedCoverageDocument.RESUMABLE
                        is RelationCheckpointDocument.RetainedOutput -> checkpoint.upstream
                    }
            }
        is OperationOutcome.Rejected -> error("Rejected relations have no retained output")
    }
