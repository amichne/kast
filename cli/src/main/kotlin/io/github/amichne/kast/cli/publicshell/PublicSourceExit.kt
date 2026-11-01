package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.protocol.contract.SourceCheckpointDocument
import io.github.amichne.kast.protocol.contract.SourcePreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.SourceQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.SourceReadLimitationDocument
import io.github.amichne.kast.protocol.contract.SourceTerminalReasonDocument
import io.github.amichne.kast.protocol.wire.presentation.ReadOutcomeStatus
import io.github.amichne.kast.protocol.wire.presentation.SourceClassificationFacts

internal fun classifySource(facts: SourceClassificationFacts, grant: PublicElapsedGrant): PublicShellExit {
    if (grant == PublicElapsedGrant.EXPLICIT && SourceReadLimitationDocument.TIME_LIMIT_REACHED in facts.limitations)
        return PublicShellExit.ELAPSED
    return when (facts.status) {
        ReadOutcomeStatus.COMPLETE -> PublicShellExit.COMPLETE
        ReadOutcomeStatus.REJECTED -> PublicShellExit.SEMANTIC
        ReadOutcomeStatus.QUALIFIED -> classifySourceProgress(facts.progress)
    }
}

private fun classifySourceProgress(progress: SourceQualifiedProgressDocument?): PublicShellExit =
    when (progress) {
        is SourceQualifiedProgressDocument.TerminalIncomplete ->
            when (progress.reason) {
                SourceTerminalReasonDocument.TEXT_PROJECTION_WITHHELD -> PublicShellExit.DELIVERY
                SourceTerminalReasonDocument.UPSTREAM_INCOMPLETE,
                SourceTerminalReasonDocument.RETENTION_CAPACITY_EXCEEDED -> PublicShellExit.SEMANTIC
            }
        is SourceQualifiedProgressDocument.Resumable ->
            when (val checkpoint = progress.checkpoint) {
                is SourceCheckpointDocument.Upstream -> PublicShellExit.SEMANTIC
                is SourceCheckpointDocument.RetainedOutput ->
                    classifySourceCoverage(checkpoint.upstream, classifyResumeAction(progress.nextAction))
            }
        is SourceQualifiedProgressDocument.RetentionUnavailable ->
            classifySourceCoverage(progress.upstream, PublicShellExit.DELIVERY)
        null -> PublicShellExit.PROTOCOL
    }

private fun classifySourceCoverage(
    coverage: SourcePreparedCoverageDocument,
    delivered: PublicShellExit,
): PublicShellExit =
    when (coverage) {
        SourcePreparedCoverageDocument.Complete -> delivered
        SourcePreparedCoverageDocument.Resumable,
        is SourcePreparedCoverageDocument.TerminalIncomplete -> PublicShellExit.SEMANTIC
    }
