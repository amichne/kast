package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalCheckpointDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalPreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.TraversalRunQualification

internal data class HostedTraversalPreparedCoverage(
    val limitations: List<TraversalLimitationDocument>,
    val relationLimitations: List<RelationLimitationDocument>,
    val upstream: TraversalPreparedCoverageDocument,
)

internal fun HostedTraversalOutcome.preparedTraversalCoverage(): HostedTraversalPreparedCoverage =
    when (this) {
        is OperationOutcome.Complete ->
            HostedTraversalPreparedCoverage(emptyList(), emptyList(), TraversalPreparedCoverageDocument.COMPLETE)
        is OperationOutcome.Qualified ->
            HostedTraversalPreparedCoverage(
                qualification.limitations,
                qualification.relationLimitations,
                when (val progress = qualification) {
                    is TraversalRunQualification.TerminalIncomplete ->
                        TraversalPreparedCoverageDocument.TERMINAL_INCOMPLETE
                    is TraversalRunQualification.Resumable ->
                        when (val checkpoint = progress.checkpoint) {
                            is TraversalCheckpointDocument.Upstream -> TraversalPreparedCoverageDocument.RESUMABLE
                            is TraversalCheckpointDocument.RetainedOutput -> checkpoint.upstream
                        }
                },
            )
        is OperationOutcome.Rejected -> error("Rejected traversals have no retained output")
    }
