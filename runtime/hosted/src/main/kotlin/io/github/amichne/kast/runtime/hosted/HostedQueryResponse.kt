package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.AdmittedQueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryPreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.protocol.contract.budgetPresence
import io.github.amichne.kast.protocol.contract.presentationPrefix
import io.github.amichne.kast.protocol.contract.presentationSuffix
import io.github.amichne.kast.protocol.contract.presentationUnitCount
import io.github.amichne.kast.protocol.contract.reason
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination

/** Actual encoded-byte pagination retains the entire suffix before publishing any prefix. */
internal fun encodeHostedQueryResponse(
    semantic: HostedQueryOutcome,
    limits: ReadLimits = ReadLimits.Default,
    observation: IntellijReadObservation = IntellijReadObservation.None,
    maximumResults: ResultLimit = ResultLimit.parse(limits[ReadLimitParameter.SEMANTIC_RESULTS].value).proven(),
    maximumBytes: ReturnedByteLimit =
        ReturnedByteLimit.parse(limits[ReadLimitParameter.HOST_RESPONSE_BYTES].value.toLong()).proven(),
    published: ((io.github.amichne.kast.query.protocol.QueryPublicationPageCharge.Encoded) -> Unit)? = null,
    retain: ((HostedQueryOutcome) -> HostedOutputRetention)? = null,
): HostedResponse =
    encodeHostedQueryResponseDocument(semantic, limits, observation, maximumResults, maximumBytes, retain, published)
        .withReadBudget(
            when (semantic) {
                is OperationOutcome.Complete -> semantic.evidence.payload.executionBudget.presence()
                is OperationOutcome.Qualified -> semantic.evidence.payload.executionBudget.presence()
                is OperationOutcome.Rejected -> semantic.reason.budgetPresence()
            }
        )

private fun encodeHostedQueryResponseDocument(
    semantic: HostedQueryOutcome,
    limits: ReadLimits,
    observation: IntellijReadObservation,
    maximumResults: ResultLimit,
    maximumBytes: ReturnedByteLimit,
    retain: ((HostedQueryOutcome) -> HostedOutputRetention)?,
    published: ((io.github.amichne.kast.query.protocol.QueryPublicationPageCharge.Encoded) -> Unit)?,
): HostedResponse {
    val original =
        HostedResponse.Canonical.encode(CanonicalOperationWireBindings.queryRun, semantic, limits, maximumBytes)
    if (original is HostedResponse.EncodingRejected) return original
    val evidence: EvidenceEnvelope<QueryRunResult>
    val limitations: List<QueryLimitationDocument>
    val minimum: QueryKnownMinimum
    when (semantic) {
        is OperationOutcome.Complete -> {
            evidence = semantic.evidence
            limitations = emptyList()
            minimum =
                evidence.payload.presentationOrigin
                    ?: QueryKnownMinimum.parse(evidence.payload.items.values.size).proven()
        }
        is OperationOutcome.Qualified -> {
            evidence = semantic.evidence
            limitations = semantic.qualification.limitations
            minimum = semantic.qualification.knownMinimum
        }
        is OperationOutcome.Rejected -> return original
    }
    val rows = evidence.payload.items.values.size
    if (original.fitsRows(rows, maximumResults)) {
        return original.publishEncodedPage(semantic, published)
    }
    if (
        (evidence.payload.invocation != null || evidence.payload.evidenceWindow != null) &&
            evidence.payload.retention != io.github.amichne.kast.protocol.contract.QueryResultRetention.NotRequested
    )
        return fitHostedAutomaticQueryPreview(semantic, limits, maximumResults, maximumBytes, published)
    if (original is HostedResponse.Oversized) observation.terminated(IntellijReadTermination.RESPONSE_BYTE_LIMIT)
    val exhausted = queryPageLimitations(limitations, original, rows, maximumResults)
    return QueryPageEncoding(evidence, minimum, exhausted, semantic.preparedCoverage(), limits, maximumBytes)
        .fit(semantic, original, retain, published, observation, maximumResults)
}

private fun HostedResponse.fitsRows(rows: Int, maximumResults: ResultLimit): Boolean =
    this !is HostedResponse.Oversized && rows <= maximumResults.value

private fun queryPageLimitations(
    existing: List<QueryLimitationDocument>,
    original: HostedResponse,
    size: Int,
    maximumResults: ResultLimit,
): List<QueryLimitationDocument> = buildList {
    addAll(existing)
    if (original is HostedResponse.Oversized) add(QueryLimitationDocument.BYTE_LIMIT_REACHED)
    if (size > maximumResults.value) add(QueryLimitationDocument.RESULT_LIMIT_REACHED)
}
    .distinct()
    .sortedBy { it.ordinal }

private class QueryPageEncoding(
    val evidence: EvidenceEnvelope<QueryRunResult>,
    val minimum: QueryKnownMinimum,
    val limitations: List<QueryLimitationDocument>,
    val upstream: QueryPreparedCoverageDocument,
    val limits: ReadLimits,
    val maximumBytes: ReturnedByteLimit,
) {
    fun fit(
        semantic: HostedQueryOutcome,
        original: HostedResponse,
        retain: ((HostedQueryOutcome) -> HostedOutputRetention)?,
        published: ((io.github.amichne.kast.query.protocol.QueryPublicationPageCharge.Encoded) -> Unit)?,
        observation: IntellijReadObservation,
        maximumResults: ResultLimit,
    ): HostedResponse {
        val rejection =
            if (original is HostedResponse.Oversized) original
            else HostedResponse.Rejected(HostedEndpointFailure.RESULT_TOO_LARGE)
        if (retain == null) return rejection
        if (evidence.payload.presentationPrefix(evidence.payload.presentationUnitCount) is Refinement.Rejected)
            return rejectedQueryRetention(
                io.github.amichne.kast.workspace.intellij.read.hosted.HostedPublicationFailureCause.INVALID_FITTED_PAGE
            )
        val rows = evidence.payload.items.values.size
        val maximumPrefix =
            if (rows > maximumResults.value) maximumResults.value else evidence.payload.presentationUnitCount - 1
        val bestCount = largestHostedQueryPrefix(maximumPrefix, ::placeholder)
        // An empty prefix cannot advance a byte-bound continuation.
        if (bestCount == 0) return rejection
        return retainPrefix(semantic, bestCount, retain, published, observation)
    }

    fun retainPrefix(
        semantic: HostedQueryOutcome,
        count: Int,
        retain: (HostedQueryOutcome) -> HostedOutputRetention,
        published: ((io.github.amichne.kast.query.protocol.QueryPublicationPageCharge.Encoded) -> Unit)?,
        observation: IntellijReadObservation,
    ): HostedResponse {
        val suffix =
            when (val selected = semantic.querySuffix(count)) {
                is Refinement.Refined -> selected.value
                is Refinement.Rejected ->
                    return rejectedQueryRetention(
                        io.github.amichne.kast.workspace.intellij.read.hosted.HostedPublicationFailureCause
                            .INVALID_FITTED_PAGE
                    )
            }
        return when (val retained = retain(suffix)) {
            is HostedOutputRetention.Retained ->
                when (val parsed = QueryExecutionContinuation.Output.parse(retained.token.value)) {
                    is Refinement.Refined -> encode(count, parsed.value, published)
                    is Refinement.Rejected ->
                        rejectedQueryRetention(
                            io.github.amichne.kast.workspace.intellij.read.hosted.HostedPublicationFailureCause
                                .INVALID_FITTED_PAGE
                        )
                }
            HostedOutputRetention.CapacityExceeded -> {
                observation.terminated(IntellijReadTermination.RETENTION_LIMIT)
                unavailable(count, published)
            }
            is HostedOutputRetention.Rejected -> rejectedQueryRetention(retained.cause)
        }
    }

    fun placeholder(count: Int): HostedResponse = encode(count, QUERY_PLACEHOLDER)

    fun encode(
        count: Int,
        token: QueryExecutionContinuation.Output,
        published: ((io.github.amichne.kast.query.protocol.QueryPublicationPageCharge.Encoded) -> Unit)? = null,
    ): HostedResponse {
        val page =
            when (
                val selected =
                    page(
                        count,
                        QueryQualifiedProgressDocument.Resumable(
                            QueryCheckpointDocument.RetainedOutput(token, upstream),
                            ReadResumeActionDocument.RESUME,
                        ),
                    )
            ) {
                is Refinement.Refined -> selected.value
                is Refinement.Rejected -> return invalidPage()
            }
        return HostedResponse.Canonical.encode(CanonicalOperationWireBindings.queryRun, page, limits, maximumBytes)
            .also { response ->
                if (response is HostedResponse.Canonical<*, *, *>) published?.invoke(encodedPublication(page, response))
            }
    }

    fun unavailable(
        maximumCount: Int,
        published: ((io.github.amichne.kast.query.protocol.QueryPublicationPageCharge.Encoded) -> Unit)?,
    ): HostedResponse {
        fun encode(count: Int): HostedResponse {
            val page =
                when (val selected = page(count, QueryQualifiedProgressDocument.RetentionUnavailable(upstream))) {
                    is Refinement.Refined -> selected.value
                    is Refinement.Rejected -> return invalidPage()
                }
            return HostedResponse.Canonical.encode(
                CanonicalOperationWireBindings.queryRun,
                page,
                limits,
                maximumBytes,
            )
        }
        val count = largestHostedQueryPrefix(maximumCount, ::encode)
        if (count == 0)
            return rejectedQueryRetention(
                io.github.amichne.kast.workspace.intellij.read.hosted.HostedPublicationFailureCause.CAPACITY_EXCEEDED
            )
        return encode(count).also { response ->
            if (response is HostedResponse.Canonical<*, *, *>) {
                when (val selected = page(count, QueryQualifiedProgressDocument.RetentionUnavailable(upstream))) {
                    is Refinement.Refined -> published?.invoke(encodedPublication(selected.value, response))
                    is Refinement.Rejected -> error("A deterministic fitted presentation changed its window proof")
                }
            }
        }
    }

    private fun page(
        count: Int,
        progress: QueryQualifiedProgressDocument,
    ): Refinement<
        io.github.amichne.kast.query.protocol.QueryPublishedPage,
        io.github.amichne.kast.protocol.contract.QueryPresentationWindowFailure,
    > {
        val payload =
            when (val selected = evidence.payload.presentationPrefix(count)) {
                is Refinement.Refined -> selected.value
                is Refinement.Rejected -> return selected
            }
        val pageLimitations =
            if (progress is QueryQualifiedProgressDocument.RetentionUnavailable)
                (limitations + QueryLimitationDocument.RETENTION_LIMIT_REACHED).distinct().sortedBy { it.ordinal }
            else limitations
        return Refinement.Refined(
            OperationOutcome.Qualified(
                evidence.copy(payload = payload),
                QueryRunQualification.create(
                        minimum,
                        pageLimitations,
                        progress,
                    )
                    .proven(),
            )
        )
    }

    private fun invalidPage(): HostedResponse =
        rejectedQueryRetention(
            io.github.amichne.kast.workspace.intellij.read.hosted.HostedPublicationFailureCause.INVALID_FITTED_PAGE
        )
}

private val QUERY_PLACEHOLDER =
    QueryExecutionContinuation.Output.parse(HostedQueryContinuations.prefix + "00000000-0000-0000-0000-000000000000")
        .proven()

private fun HostedQueryOutcome.querySuffix(
    count: Int
): Refinement<HostedQueryOutcome, io.github.amichne.kast.protocol.contract.QueryPresentationWindowFailure> {
    return when (this) {
        is OperationOutcome.Complete -> {
            val suffix =
                when (val selected = evidence.payload.presentationSuffix(count)) {
                    is Refinement.Refined -> selected.value
                    is Refinement.Rejected -> return selected
                }
            Refinement.Refined(
                OperationOutcome.Complete(
                    evidence.copy(
                        payload =
                            suffix.copy(
                                presentationOrigin =
                                    evidence.payload.presentationOrigin
                                        ?: QueryKnownMinimum.parse(evidence.payload.items.values.size).proven()
                            )
                    )
                )
            )
        }
        is OperationOutcome.Qualified -> {
            when (val selected = evidence.payload.presentationSuffix(count)) {
                is Refinement.Refined ->
                    Refinement.Refined(
                        OperationOutcome.Qualified(evidence.copy(payload = selected.value), qualification)
                    )
                is Refinement.Rejected -> selected
            }
        }
        is OperationOutcome.Rejected -> Refinement.Refined(this)
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.proven(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("A subset of an admitted query result violated its contract")
    }

private fun largestHostedQueryPrefix(maximum: Int, encode: (Int) -> HostedResponse): Int {
    var bestCount = 0
    var lower = 1
    var upper = maximum
    while (lower <= upper) {
        val count = lower + (upper - lower) / 2
        when (encode(count)) {
            is HostedResponse.Canonical<*, *, *> -> {
                bestCount = count
                lower = count + 1
            }
            is HostedResponse.Oversized -> upper = count - 1
            else -> return 0
        }
    }
    return bestCount
}

private fun rejectedQueryRetention(
    cause: io.github.amichne.kast.workspace.intellij.read.hosted.HostedPublicationFailureCause
): HostedResponse =
    HostedResponse.ReadRejected(
        io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryFailure.Publication(cause),
        io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryStage.RESULT_DETACHED,
    )

internal fun HostedQueryOutcome.withQueryBudget(
    report: io.github.amichne.kast.protocol.contract.ExecutionBudgetReport?
): HostedQueryOutcome =
    when (this) {
        is OperationOutcome.Complete ->
            OperationOutcome.Complete(evidence.copy(payload = evidence.payload.copy(executionBudget = report)))
        is OperationOutcome.Qualified ->
            OperationOutcome.Qualified(
                evidence.copy(payload = evidence.payload.copy(executionBudget = report)),
                qualification,
            )
        is OperationOutcome.Rejected ->
            if (report == null) this else OperationOutcome.Rejected(AdmittedQueryRunRejection(reason.reason(), report))
    }

/** This projection preserves the closed semantic reason while per-call budget reports stay at presentation. */
internal fun HostedQueryOutcome.publicationPage(): io.github.amichne.kast.query.protocol.QueryPublishedPage =
    when (this) {
        is OperationOutcome.Complete ->
            OperationOutcome.Complete(evidence.copy(payload = evidence.payload.copy(executionBudget = null)))
        is OperationOutcome.Qualified ->
            OperationOutcome.Qualified(
                evidence.copy(payload = evidence.payload.copy(executionBudget = null)),
                qualification,
            )
        is OperationOutcome.Rejected -> OperationOutcome.Rejected(reason.reason())
    }

/** Carries the successful encoding bound into the existing publication owner without encoding again. */
internal fun encodedPublication(
    page: io.github.amichne.kast.query.protocol.QueryPublishedPage,
    response: HostedResponse.Canonical<*, *, *>,
): io.github.amichne.kast.query.protocol.QueryPublicationPageCharge.Encoded =
    io.github.amichne.kast.query.protocol.QueryPublicationPageCharge.Encoded.fromEncoding(
        page.publicationPage(),
        io.github.amichne.kast.query.protocol.QueryRetentionByteCount.parse(
                response.document.toByteArray(Charsets.UTF_8).size.toLong()
            )
            .proven(),
    )

private fun HostedResponse.publishEncodedPage(
    page: io.github.amichne.kast.query.protocol.QueryPublishedPage,
    published: ((io.github.amichne.kast.query.protocol.QueryPublicationPageCharge.Encoded) -> Unit)?,
): HostedResponse {
    if (this is HostedResponse.Canonical<*, *, *>) published?.invoke(encodedPublication(page, this))
    return this
}
