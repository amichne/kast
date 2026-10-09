package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryStaticModelDocument
import io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument
import io.github.amichne.kast.query.contract.QueryRetainedResult

/** A complete retained enumeration does not reverse the original static-policy rejection. */
internal fun completionEvidenceRead(
    page: QueryPublishedPage,
    request: QueryRunRequest.Run,
    original: QueryRetainedResult,
    state: QueryStateStore,
): QueryPublishedPage {
    val failure =
        when (val proof = completionProof(original)) {
            is Refinement.Refined -> return page
            is Refinement.Rejected -> proof.failure
        }
    val envelope =
        when (page) {
            is OperationOutcome.Complete -> page.evidence
            is OperationOutcome.Qualified -> page.evidence
            is OperationOutcome.Rejected -> return page
        }
    val source = (page as? OperationOutcome.Qualified)?.qualification
    val count =
        source?.knownMinimum
            ?: envelope.payload.presentationOrigin
            ?: when (val parsed = QueryKnownMinimum.parse(original.rowCount)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> error("Retained row count cannot be negative")
            }
    val limitations =
        (source?.limitations.orEmpty() + QueryLimitationDocument.STATIC_MODEL_UNPROVEN).distinct().sortedBy {
            it.ordinal
        }
    val progress = QueryQualifiedProgressDocument.TerminalIncomplete(QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE)
    val originalCoverage = completionOriginalCoverage(original, request, state, progress)
    val qualification =
        when (val admitted = QueryRunQualification.create(count, limitations, progress)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> error("Completion evidence qualification must preserve admitted coverage")
        }
    return OperationOutcome.Qualified(
        envelope.copy(
            payload =
                envelope.payload.copy(
                    interpretation =
                        QueryResultInterpretationDocument.EvidenceOnly(
                            QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1,
                            failure.protocolCompletionCause(),
                            originalCoverage,
                        )
                )
        ),
        qualification,
    )
}

private fun completionOriginalCoverage(
    original: QueryRetainedResult,
    request: QueryRunRequest.Run,
    state: QueryStateStore,
    progress: QueryQualifiedProgressDocument,
): io.github.amichne.kast.protocol.contract.QueryCompletionCoverageDocument {
    return when (val coverage = original.coverage) {
        is io.github.amichne.kast.query.contract.QueryCoverage.Complete ->
            io.github.amichne.kast.protocol.contract.QueryCompletionCoverageDocument.Complete
        is io.github.amichne.kast.query.contract.QueryCoverage.Qualified ->
            io.github.amichne.kast.protocol.contract.QueryCompletionCoverageDocument.Qualified(
                (io.github.amichne.kast.protocol.contract.ProtocolOffset.parse(coverage.knownMinimum.value)
                        as Refinement.Refined)
                    .value,
                (io.github.amichne.kast.protocol.contract.QueryCompletionLimitationsDocument.from(
                        coverage.limitations.map { QueryLimitationDocument.valueOf(it.name) }
                    ) as Refinement.Refined)
                    .value,
                original.producerProgress?.let {
                    projectQueryProgress(request, it, state, origin = QueryProgressOrigin.RETAINED_RESULT)
                } ?: progress,
            )
    }
}
