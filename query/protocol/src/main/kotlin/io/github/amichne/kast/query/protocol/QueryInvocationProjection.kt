package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.MAX_PROTOCOL_ITEMS
import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryEvidenceCursor
import io.github.amichne.kast.protocol.contract.QueryEvidenceWindowDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryInvocationDocument
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
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
import io.github.amichne.kast.query.contract.QueryImpactWitnessView
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryValuePathAccounting
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
    ): QueryPublishedPage =
        when (val recovered = completionCheckpointEvidence(request, accumulated, policy, authority)) {
            is Refinement.Refined -> Projection(request, lease, recovered.value, policy, owner).project()
            is Refinement.Rejected -> OperationOutcome.Rejected(recovered.failure)
        }

    private inner class Projection(
        private val request: QueryRunRequest.Run,
        private val lease: SemanticReadAuthority,
        private val accumulated: AccumulatedSymbolQuery,
        private val policy: QueryInvocationPolicy,
        private val owner: QueryExecutionClaim,
    ) {
        private val execution = accumulated.execution
        private val witnessOutput = request.output as? QueryOutputDocument.ImpactWitness
        private val count =
            witnessOutput?.let { output ->
                val rows = executionResultRows(execution) as? QueryRows.ValuePaths
                val ledger = (rows?.accounting as? QueryValuePathAccounting.Investigated)?.ledger
                ledger?.let { QueryImpactWitnessView.count(it, output.section.witnessSection()).required().value }
            } ?: accumulated.items.size
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
            when (val admitted = admitCompletion()) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admitted.failure
            }
            val evidence =
                when (
                    val selected =
                        QueryEvidencePresentation.create(
                            raw.copy(rows = emptyInvocationRows(raw.rows)),
                            QueryEvidenceCursor.Start,
                            policy.previewRows,
                        )
                ) {
                    is Refinement.Refined -> selected.value
                    is Refinement.Rejected ->
                        return OperationOutcome.Rejected(QueryRunRejection.ExecutionRejected(selected.failure))
                }
            val projectedEvidence =
                when (
                    val projected =
                        QueryProjectedEvidence.from(
                            result = evidence.result,
                            output = if (witnessOutput != null) QueryOutputDocument.ValuePaths else request.output,
                            authority = authority,
                        )
                ) {
                    is Refinement.Refined -> projected.value
                    is Refinement.Rejected -> return OperationOutcome.Rejected(projected.failure)
                }
            if (!retentionRequired(evidence.window)) {
                val inline = present(projectedEvidence, null, evidence.window)
                when (policy.inlinePresentation(inline)) {
                    QueryInlinePresentation.FITS -> return inline
                    QueryInlinePresentation.RETENTION_REQUIRED -> Unit
                    QueryInlinePresentation.INVALID -> return contractRejected()
                }
            }
            return presentRetained(projectedEvidence, evidence.window)
        }

        private fun admitCompletion(): Refinement<Unit, QueryPublishedPage> {
            val completion =
                request.completion as? QueryCompletionPolicyDocument.CompleteOnly ?: return Refinement.Refined(Unit)
            return when (val proof = completionProof(execution)) {
                is Refinement.Refined -> proof
                is Refinement.Rejected ->
                    Refinement.Rejected(
                        fitQueryCompletionRejection(
                            rejectInvocationCompletion(
                                completion,
                                proof.failure,
                                accumulated,
                                progress,
                                ::completionEvidence,
                            ),
                            policy.inlinePresentation,
                            ::contractRejected,
                        )
                    )
            }
        }

        private fun completionEvidence(): Refinement<QueryCompletionEvidenceDocument, QueryRunRejection> =
            completionQueryEvidence(retain(), QueryQuestionDocument.from(request)) { issued ->
                when (val selected = preview(issued)) {
                    is Refinement.Refined -> Refinement.Refined(selected.value.items)
                    is Refinement.Rejected -> selected
                }
            }

        private fun presentRetained(
            evidence: QueryProjectedEvidence,
            window: QueryEvidenceWindowDocument,
        ): QueryPublishedPage =
            when (val retained = retain()) {
                is Refinement.Refined -> present(evidence, retained.value, window)
                is Refinement.Rejected -> OperationOutcome.Rejected(retained.failure)
            }

        private fun present(
            evidence: QueryProjectedEvidence,
            issuance: QueryResultIssuance?,
            evidenceWindow: QueryEvidenceWindowDocument,
        ): QueryPublishedPage {
            val preview =
                when (val fitted = preview(issuance)) {
                    is Refinement.Refined -> fitted.value
                    is Refinement.Rejected -> return OperationOutcome.Rejected(fitted.failure)
                }
            val presentationEvidence =
                when (
                    val selected =
                        invocationAccounting(executionResultRows(execution), witnessOutput, preview.items.values.size)
                ) {
                    is Refinement.Refined -> selected.value?.let { evidence.copy(impactAccounting = it) } ?: evidence
                    is Refinement.Rejected -> return OperationOutcome.Rejected(selected.failure)
                }
            val envelope =
                authority.resultEnvelope(
                    lease = lease,
                    question = QueryQuestionDocument.from(request),
                    presented =
                        PresentedQueryRows(preview.items, preview.retention, QueryPresentedWindowSelection.NotRetained),
                    evidence = presentationEvidence,
                    presentationWindow = preview.window,
                    presentationOrigin = QueryKnownMinimum.parse(count).required(),
                )
            return coverage(
                envelope.copy(
                    payload = envelope.payload.copy(invocation = preview.summary, evidenceWindow = evidenceWindow)
                ),
                preview.retention,
            )
        }

        private fun retentionRequired(evidenceWindow: QueryEvidenceWindowDocument): Boolean =
            witnessOutput != null ||
                request.retention == QueryRetentionModeDocument.RETAIN ||
                evidenceWindow.nextCursor != null ||
                count > minOf(policy.previewRows.value, MAX_PROTOCOL_ITEMS) ||
                policy.previewBytes(accumulated.items) > policy.previewBytesLimit.value

        private fun retain(): Refinement<QueryResultIssuance, QueryRunRejection> {
            observation.observe(QueryResultRetentionEvidence.CaptureStarted(QueryResultRetentionScope.PRESENTED))
            val snapshot =
                when (
                    val captured =
                        if (
                            request.from is QueryFromDocument.Impact &&
                                executionResultRows(execution) is QueryRows.ValuePaths &&
                                (executionResultRows(execution) as QueryRows.ValuePaths).accounting is
                                    QueryValuePathAccounting.Investigated
                        )
                            QueryRetainedResult.captureInvestigation(lease, execution)
                        else QueryRetainedResult.capture(lease, execution)
                ) {
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

        private fun previewCandidates(
            retained: QueryResultIssuance.Issued?
        ): Refinement<List<QueryResultItemDocument>, QueryRunRejection> {
            if (witnessOutput != null)
                return invocationWitnessPreview(
                    executionResultRows(execution),
                    witnessOutput,
                    retained,
                    minOf(policy.previewRows.value, MAX_PROTOCOL_ITEMS, count),
                )
            return when (
                val identified =
                    identifyRows(
                        accumulated.items.take(minOf(policy.previewRows.value, MAX_PROTOCOL_ITEMS)),
                        retained?.rowIds?.take(minOf(policy.previewRows.value, MAX_PROTOCOL_ITEMS)),
                    )
            ) {
                is QueryProjection.Projected -> Refinement.Refined(identified.values)
                QueryProjection.Rejected -> contractFailure()
            }
        }

        private fun preview(issuance: QueryResultIssuance?): Refinement<InvocationPreview, QueryRunRejection> {
            val originalFailure =
                when (val admitted = optionalOriginalFailure(accumulated.failure)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return Refinement.Rejected(admitted.failure)
                }
            val retained = issuance as? QueryResultIssuance.Issued
            val candidates =
                when (val identified = previewCandidates(retained)) {
                    is Refinement.Refined -> identified.value
                    is Refinement.Rejected -> return identified
                }
            var end = 0
            while (
                end < candidates.size && policy.previewBytes(candidates.take(end + 1)) <= policy.previewBytesLimit.value
            ) end++
            val rows = candidates.take(end)
            val bytes = policy.previewBytes(rows)
            if (bytes !in 2..policy.previewBytesLimit.value) return contractFailure()
            val retention = previewRetention(issuance)
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
                        failure = originalFailure,
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
                if (failed) QueryQualifiedProgressDocument.RetentionUnavailable(invocationUpstreamCoverage(progress))
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

private fun executionResultRows(execution: QueryExecutionResult): QueryRows? =
    when (execution) {
        is QueryExecutionResult.Complete -> execution.result.rows
        is QueryExecutionResult.Qualified -> execution.result.rows
        is QueryExecutionResult.Rejection -> null
    }

private fun emptyInvocationRows(rows: QueryRows): QueryRows =
    when (rows) {
        is QueryRows.Symbols -> QueryRows.Symbols.of(emptyList())
        is QueryRows.Occurrences -> QueryRows.Occurrences.of(emptyList())
        is QueryRows.Bindings -> QueryRows.Bindings.of(emptyList(), rows.mode)
        is QueryRows.ValuePaths -> rows.selectRows(emptyList()).required()
        is QueryRows.ImpactWitness -> error("Invocation admitted presentation-only rows")
    }

private fun invocationUpstreamCoverage(progress: QueryQualifiedProgressDocument?): QueryPreparedCoverageDocument =
    when (val original = progress) {
        null -> QueryPreparedCoverageDocument.Complete
        is QueryQualifiedProgressDocument.Resumable -> QueryPreparedCoverageDocument.Resumable
        is QueryQualifiedProgressDocument.TerminalIncomplete ->
            QueryPreparedCoverageDocument.TerminalIncomplete(original.reason)
        is QueryQualifiedProgressDocument.RetentionUnavailable -> original.upstream
    }
