package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.AdmittedQueryRunRejection
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryPreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
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
    retain: ((HostedQueryOutcome) -> HostedOutputRetention)? = null,
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
            minimum = QueryKnownMinimum.parse(evidence.payload.items.values.size).proven()
        }
        is OperationOutcome.Qualified -> {
            evidence = semantic.evidence
            limitations = semantic.qualification.limitations
            minimum = semantic.qualification.knownMinimum
        }
        is OperationOutcome.Rejected -> return original
    }
    val size = evidence.payload.items.values.size
    if (original !is HostedResponse.Oversized && size <= maximumResults.value) return original
    if (original is HostedResponse.Oversized) observation.terminated(IntellijReadTermination.RESPONSE_BYTE_LIMIT)
    val rejection =
        if (original is HostedResponse.Oversized) original
        else HostedResponse.Rejected(HostedEndpointFailure.RESULT_TOO_LARGE)
    // Without a continuation owner no prefix may be irreversibly published.
    if (retain == null) return rejection
    val exhausted = queryPageLimitations(limitations, original, size, maximumResults)
    val fitting = QueryPageEncoding(evidence, minimum, exhausted, semantic.preparedCoverage(), limits, maximumBytes)
    val bestCount = largestHostedQueryPrefix(minOf(size - 1, maximumResults.value), fitting::placeholder)
    // An empty prefix cannot advance a byte-bound continuation. Fail with finite rejection instead.
    if (bestCount == 0) return rejection
    return retain(semantic.querySuffix(bestCount)).encodeOr(rejection) { token -> fitting.encode(bestCount, token) }
}

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
    fun placeholder(count: Int): HostedResponse = encode(count, QUERY_PLACEHOLDER)

    fun encode(count: Int, token: ProtocolText): HostedResponse =
        HostedResponse.Canonical.encode(
            CanonicalOperationWireBindings.queryRun,
            OperationOutcome.Qualified(
                evidence.copy(
                    payload =
                        evidence.payload.copy(
                            items = BoundedProtocolList.create(evidence.payload.items.values.take(count)).proven()
                        )
                ),
                QueryRunQualification.create(
                        minimum,
                        limitations,
                        QueryQualifiedProgressDocument.Resumable(
                            QueryCheckpointDocument.RetainedOutput(token, upstream),
                            ReadResumeActionDocument.RESUME,
                        ),
                    )
                    .proven(),
            ),
            limits,
            maximumBytes,
        )
}

private val QUERY_PLACEHOLDER =
    ProtocolText.parse(HostedQueryContinuations.prefix + "00000000-0000-0000-0000-000000000000").proven()

private fun HostedQueryOutcome.querySuffix(count: Int): HostedQueryOutcome {
    fun EvidenceEnvelope<QueryRunResult>.suffix() =
        copy(payload = payload.copy(items = BoundedProtocolList.create(payload.items.values.drop(count)).proven()))
    return when (this) {
        is OperationOutcome.Complete -> OperationOutcome.Complete(evidence.suffix())
        is OperationOutcome.Qualified -> OperationOutcome.Qualified(evidence.suffix(), qualification)
        is OperationOutcome.Rejected -> this
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

private fun HostedOutputRetention.encodeOr(
    original: HostedResponse,
    encode: (ProtocolText) -> HostedResponse,
): HostedResponse =
    when (this) {
        is HostedOutputRetention.Retained -> encode(token)
        HostedOutputRetention.CapacityExceeded,
        HostedOutputRetention.EncodingRejected -> original
    }

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
