package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryPreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument

/** Pure store fixtures publish the successor they issued, rather than orphaning private work. */
internal fun QueryPublishedPage.advertisingOutput(token: QueryExecutionContinuation.Output): QueryPublishedPage {
    val evidence =
        when (this) {
            is OperationOutcome.Complete -> evidence
            is OperationOutcome.Qualified -> evidence
            is OperationOutcome.Rejected -> error("Rejected evidence cannot advertise an output suffix")
        }
    val minimum =
        when (this) {
            is OperationOutcome.Qualified -> qualification.knownMinimum
            else -> (QueryKnownMinimum.parse(evidence.payload.items.values.size) as Refinement.Refined).value
        }
    val limitations =
        ((this as? OperationOutcome.Qualified)?.qualification?.limitations.orEmpty() +
                QueryLimitationDocument.RESULT_LIMIT_REACHED)
            .distinct()
            .sortedBy { it.ordinal }
    val upstream =
        when (this) {
            is OperationOutcome.Complete -> QueryPreparedCoverageDocument.Complete
            is OperationOutcome.Qualified ->
                when (val progress = qualification.progress) {
                    is QueryQualifiedProgressDocument.Resumable ->
                        when (val checkpoint = progress.checkpoint) {
                            is QueryCheckpointDocument.Upstream -> QueryPreparedCoverageDocument.Resumable
                            is QueryCheckpointDocument.RetainedOutput -> checkpoint.upstream
                        }
                    is QueryQualifiedProgressDocument.TerminalIncomplete ->
                        QueryPreparedCoverageDocument.TerminalIncomplete(progress.reason)
                    is QueryQualifiedProgressDocument.RetentionUnavailable -> progress.upstream
                }
            else -> error("Unreachable rejected fixture")
        }
    return OperationOutcome.Qualified(
        evidence,
        (QueryRunQualification.create(
                minimum,
                limitations,
                QueryQualifiedProgressDocument.Resumable(
                    QueryCheckpointDocument.RetainedOutput(token, upstream),
                    ReadResumeActionDocument.RESUME,
                ),
            ) as Refinement.Refined)
            .value,
    )
}
