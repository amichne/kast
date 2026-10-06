package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.MAX_PROTOCOL_ITEMS
import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryInvocationDocument
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryPreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryPreviewDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRetainedPresentationWindow
import io.github.amichne.kast.protocol.contract.QueryRetentionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

/** Final invocation presentation uses the existing retained state and canonical evidence projection. */
internal class QueryInvocationProjection(
    private val authority: QueryReferenceAuthority,
    private val state: QueryStateStore,
    private val observation: QueryResultRetentionObservation,
) {
    fun project(
        request: QueryRunRequest.Run,
        lease: SemanticReadAuthority,
        accumulated: AccumulatedSymbolQuery,
        policy: QueryInvocationPolicy,
        owner: QueryExecutionClaim,
    ): QueryPublishedPage = Projection(request, lease, accumulated, policy, owner).project()

    private inner class Projection(
        private val request: QueryRunRequest.Run,
        private val lease: SemanticReadAuthority,
        private val accumulated: AccumulatedSymbolQuery,
        private val policy: QueryInvocationPolicy,
        private val owner: QueryExecutionClaim,
    ) {
        private val execution = accumulated.execution
        private val count = accumulated.items.size
        private val qualified = execution as? QueryExecutionResult.Qualified
        private val progress = qualified?.let { result ->
            when (result.continuation) {
                is QueryContinuationState.Terminal -> projectQueryProgress(request, result.continuation, state)
                is QueryContinuationState.Resumable -> retainedProgress()
            }
        }

        private fun retainedProgress(): QueryQualifiedProgressDocument {
            val issued = accumulated.issuedProgress ?: return unavailableProgress()
            val token = (issued.checkpoint as? QueryCheckpointDocument.Upstream)?.token
            return if (token != null && state.restoreCheckpoint(token, lease) is QueryCheckpointRestoration.Restored)
                issued
            else unavailableProgress()
        }

        private fun unavailableProgress() =
            QueryQualifiedProgressDocument.TerminalIncomplete(QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE)

        fun project(): QueryPublishedPage {
            val raw =
                when (execution) {
                    is QueryExecutionResult.Complete -> execution.result
                    is QueryExecutionResult.Qualified -> execution.result
                    is QueryExecutionResult.Rejection -> return contractRejected()
                }
            val evidence =
                when (
                    val projected =
                        QueryProjectedEvidence.from(
                            result = raw.copy(rows = QueryRows.Symbols.of(emptyList())),
                            output = request.output,
                            authority = authority,
                        )
                ) {
                    is Refinement.Refined -> projected.value
                    is Refinement.Rejected -> return OperationOutcome.Rejected(projected.failure)
                }
            if (!retentionRequired()) {
                val inline = present(evidence, null)
                when (policy.inlinePresentation(inline)) {
                    QueryInlinePresentation.FITS -> return inline
                    QueryInlinePresentation.RETENTION_REQUIRED -> Unit
                    QueryInlinePresentation.INVALID -> return contractRejected()
                }
            }
            return when (val retained = retain()) {
                is Refinement.Refined -> present(evidence, retained.value)
                is Refinement.Rejected -> OperationOutcome.Rejected(retained.failure)
            }
        }

        private fun present(evidence: QueryProjectedEvidence, issuance: QueryResultIssuance?): QueryPublishedPage {
            val preview =
                when (val fitted = preview(issuance)) {
                    is Refinement.Refined -> fitted.value
                    is Refinement.Rejected -> return OperationOutcome.Rejected(fitted.failure)
                }
            val envelope =
                authority.resultEnvelope(
                    lease = lease,
                    question = QueryQuestionDocument.from(request),
                    presented =
                        PresentedQueryRows(preview.items, preview.retention, QueryPresentedWindowSelection.NotRetained),
                    evidence = evidence,
                    presentationWindow = preview.window,
                    presentationOrigin = QueryKnownMinimum.parse(count).required(),
                )
            return coverage(
                envelope.copy(payload = envelope.payload.copy(invocation = preview.summary)),
                preview.retention,
            )
        }

        private fun retentionRequired(): Boolean =
            request.retention == QueryRetentionModeDocument.RETAIN ||
                count > minOf(policy.previewRows.value, MAX_PROTOCOL_ITEMS) ||
                policy.previewBytes(accumulated.items) > policy.previewBytesLimit.value

        private fun retain(): Refinement<QueryResultIssuance, QueryRunRejection> {
            observation.observe(QueryResultRetentionEvidence.CaptureStarted(QueryResultRetentionScope.PRESENTED))
            val snapshot =
                when (val captured = QueryRetainedResult.capture(lease, execution)) {
                    is Refinement.Refined -> captured.value
                    is Refinement.Rejected -> return contractFailure()
                }
            observation.observe(QueryResultRetentionEvidence.Captured(QueryResultRetentionScope.PRESENTED))
            val checkpoint =
                ((progress as? QueryQualifiedProgressDocument.Resumable)?.checkpoint
                        as? QueryCheckpointDocument.Upstream)
                    ?.token
            val issued =
                state.issueResult(
                    request = request,
                    result = snapshot,
                    protectedCheckpoint = checkpoint,
                    publicationOwner = owner,
                )
            observation.observe(
                QueryResultRetentionEvidence.Issuance(
                    when (issued) {
                        is QueryResultIssuance.Issued -> QueryResultRetentionIssue.ISSUED
                        QueryResultIssuance.Unavailable -> QueryResultRetentionIssue.UNAVAILABLE
                        QueryResultIssuance.CapacityExceeded -> QueryResultRetentionIssue.CAPACITY_EXCEEDED
                    }
                )
            )
            return Refinement.Refined(issued)
        }

        private fun preview(issuance: QueryResultIssuance?): Refinement<InvocationPreview, QueryRunRejection> {
            val retained = issuance as? QueryResultIssuance.Issued
            val candidates =
                accumulated.items.take(minOf(policy.previewRows.value, MAX_PROTOCOL_ITEMS)).mapIndexed { index, row ->
                    row.copy(rowId = retained?.rowIds?.get(index))
                }
            var end = 0
            while (
                end < candidates.size && policy.previewBytes(candidates.take(end + 1)) <= policy.previewBytesLimit.value
            ) end++
            val rows = candidates.take(end)
            val bytes = policy.previewBytes(rows)
            if (bytes !in 2..policy.previewBytesLimit.value) return contractFailure()
            val retention =
                when (issuance) {
                    null -> QueryResultRetention.NotRequested
                    is QueryResultIssuance.Issued -> QueryResultRetention.Retained(issuance.reference)
                    QueryResultIssuance.Unavailable,
                    QueryResultIssuance.CapacityExceeded -> QueryResultRetention.CapacityExceeded
                }
            val window = retained?.let { issued ->
                QueryRetainedPresentationWindow.create(
                        issued.reference,
                        QueryResultCursor.Start,
                        QueryResultCursor.parse(end).required(),
                        QueryResultCursor.parse(count).required(),
                    )
                    .required()
            }
            val summary =
                QueryInvocationDocument.create(
                        count = count,
                        preview =
                            if (end == count) QueryPreviewDocument.Inline(end, bytes)
                            else QueryPreviewDocument.Prefix(end, bytes),
                        stop =
                            if (retention == QueryResultRetention.CapacityExceeded) QueryInvocationStop.RETENTION_FAILED
                            else accumulated.stop,
                        failure = accumulated.failure,
                    )
                    .required()
            return Refinement.Refined(
                InvocationPreview(
                    BoundedProtocolList.create<QueryResultItemDocument>(rows).required(),
                    retention,
                    window,
                    summary,
                )
            )
        }

        private fun coverage(
            envelope: EvidenceEnvelope<QueryRunResult>,
            retention: QueryResultRetention,
        ): QueryPublishedPage {
            val failed = retention == QueryResultRetention.CapacityExceeded
            if (execution is QueryExecutionResult.Complete && !failed) return OperationOutcome.Complete(envelope)
            val limitations =
                qualified?.coverage?.limitations.orEmpty().map { QueryLimitationDocument.valueOf(it.name) }.toSet() +
                    if (failed) setOf(QueryLimitationDocument.RETENTION_LIMIT_REACHED) else emptySet()
            val finalProgress =
                if (failed) QueryQualifiedProgressDocument.RetentionUnavailable(upstreamCoverage())
                else progress ?: return contractRejected()
            val qualification =
                QueryRunQualification.create(
                        QueryKnownMinimum.parse(count).required(),
                        limitations.sortedBy { it.ordinal },
                        finalProgress,
                    )
                    .required()
            return OperationOutcome.Qualified(envelope, qualification)
        }

        private fun upstreamCoverage(): QueryPreparedCoverageDocument =
            when (val original = progress) {
                null -> QueryPreparedCoverageDocument.Complete
                is QueryQualifiedProgressDocument.Resumable -> QueryPreparedCoverageDocument.Resumable
                is QueryQualifiedProgressDocument.TerminalIncomplete ->
                    QueryPreparedCoverageDocument.TerminalIncomplete(original.reason)
                is QueryQualifiedProgressDocument.RetentionUnavailable -> original.upstream
            }
    }

    private data class InvocationPreview(
        val items: BoundedProtocolList<QueryResultItemDocument>,
        val retention: QueryResultRetention,
        val window: QueryRetainedPresentationWindow?,
        val summary: QueryInvocationDocument,
    )

    private fun contractFailure() =
        Refinement.Rejected(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
        )

    private fun contractRejected(): OperationOutcome.Rejected<QueryRunRejection> =
        OperationOutcome.Rejected(contractFailure().failure)
}
