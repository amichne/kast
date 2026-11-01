package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryPreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument
import io.github.amichne.kast.protocol.wire.presentation.QueryClassificationFacts
import io.github.amichne.kast.protocol.wire.presentation.ReadOutcomeStatus

internal fun classifySemantic(facts: QueryClassificationFacts, grant: PublicElapsedGrant): PublicShellExit {
    if (grant == PublicElapsedGrant.EXPLICIT && facts.stop == QueryInvocationStop.TIME_LIMIT)
        return PublicShellExit.ELAPSED
    return classifyQueryProof(facts)
}

private fun classifyQueryProof(facts: QueryClassificationFacts): PublicShellExit {
    when (facts.interpretation) {
        is QueryResultInterpretationDocument.EvidenceOnly -> return PublicShellExit.SEMANTIC
        QueryResultInterpretationDocument.QueryResult,
        is QueryResultInterpretationDocument.SourceRelationships,
        null -> Unit
    }
    return when (facts.status) {
        ReadOutcomeStatus.REJECTED -> PublicShellExit.SEMANTIC
        ReadOutcomeStatus.COMPLETE ->
            when (facts.coverage?.exhaustive) {
                true -> PublicShellExit.COMPLETE
                false -> PublicShellExit.SEMANTIC
                null -> PublicShellExit.PROTOCOL
            }
        ReadOutcomeStatus.QUALIFIED -> classifyQueryProgress(facts.progress)
    }
}

private fun classifyQueryProgress(progress: QueryQualifiedProgressDocument?): PublicShellExit =
    when (progress) {
        is QueryQualifiedProgressDocument.Resumable ->
            when (val checkpoint = progress.checkpoint) {
                is QueryCheckpointDocument.Upstream -> PublicShellExit.SEMANTIC
                is QueryCheckpointDocument.RetainedOutput ->
                    classifyQueryCoverage(checkpoint.upstream, classifyResumeAction(progress.nextAction))
            }
        is QueryQualifiedProgressDocument.RetentionUnavailable ->
            classifyQueryCoverage(progress.upstream, PublicShellExit.DELIVERY)
        is QueryQualifiedProgressDocument.TerminalIncomplete,
        null -> PublicShellExit.SEMANTIC
    }

private fun classifyQueryCoverage(
    coverage: QueryPreparedCoverageDocument,
    delivered: PublicShellExit,
): PublicShellExit =
    when (coverage) {
        QueryPreparedCoverageDocument.Complete -> delivered
        QueryPreparedCoverageDocument.Resumable,
        is QueryPreparedCoverageDocument.TerminalIncomplete -> PublicShellExit.SEMANTIC
    }
