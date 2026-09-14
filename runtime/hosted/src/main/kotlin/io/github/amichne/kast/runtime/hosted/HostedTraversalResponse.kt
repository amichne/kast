package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalCheckpointDocument
import io.github.amichne.kast.protocol.contract.TraversalContinuationDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalPreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings

/** Retain detached graph records before publishing; partial expansions and committed work remain mandatory. */
internal fun encodeHostedTraversalResponse(
    semantic: HostedTraversalOutcome,
    limits: ReadLimits,
    maximumResults: ResultLimit,
    maximumBytes: ReturnedByteLimit,
    retain: (HostedTraversalOutcome) -> HostedOutputRetention,
): HostedResponse {
    val original =
        HostedResponse.Canonical.encode(CanonicalOperationWireBindings.traversalRun, semantic, limits, maximumBytes)
    if (original is HostedResponse.EncodingRejected || semantic is OperationOutcome.Rejected) return original
    val evidence =
        when (semantic) {
            is OperationOutcome.Complete -> semantic.evidence
            is OperationOutcome.Qualified -> semantic.evidence
        }
    val size = evidence.payload.records.values.size
    if (original !is HostedResponse.Oversized && size <= maximumResults.value) return original
    val retained = semantic.preparedTraversalCoverage()
    val exhausted = buildList {
        addAll(retained.limitations)
        if (original is HostedResponse.Oversized) add(TraversalLimitationDocument.BYTE_LIMIT_REACHED)
        if (size > maximumResults.value) add(TraversalLimitationDocument.RECORD_LIMIT_REACHED)
    }
        .distinct()
        .sortedBy { it.ordinal }
    val fitting =
        TraversalPageEncoding(
            evidence,
            exhausted,
            retained.relationLimitations,
            retained.upstream,
            limits,
            maximumBytes,
        )
    val count = largestFittingTraversalPrefix(minOf(size - 1, maximumResults.value), fitting::placeholder)
    if (count == 0) return HostedResponse.Rejected(HostedEndpointFailure.RESULT_TOO_LARGE)
    val remainder =
        evidence.copy(
            payload =
                evidence.payload.copy(
                    records = BoundedProtocolList.create(evidence.payload.records.values.drop(count)).proven()
                )
        )
    val suffix =
        when (semantic) {
            is OperationOutcome.Complete -> OperationOutcome.Complete(remainder)
            is OperationOutcome.Qualified -> OperationOutcome.Qualified(remainder, semantic.qualification)
        }
    return when (val result = retain(suffix)) {
        is HostedOutputRetention.Retained -> fitting.encode(count, result.token)
        HostedOutputRetention.CapacityExceeded -> HostedResponse.Rejected(HostedEndpointFailure.RESULT_TOO_LARGE)
        HostedOutputRetention.EncodingRejected -> HostedResponse.Rejected(HostedEndpointFailure.RESPONSE_REJECTED)
    }
}

private class TraversalPageEncoding(
    private val evidence: EvidenceEnvelope<TraversalRunResult>,
    private val limitations: List<TraversalLimitationDocument>,
    private val relationLimitations: List<RelationLimitationDocument>,
    private val upstream: TraversalPreparedCoverageDocument,
    private val limits: ReadLimits,
    private val maximumBytes: ReturnedByteLimit,
) {
    fun placeholder(count: Int) = encode(count, PLACEHOLDER)

    fun encode(count: Int, token: ProtocolText): HostedResponse =
        HostedResponse.Canonical.encode(
            CanonicalOperationWireBindings.traversalRun,
            OperationOutcome.Qualified(
                evidence.copy(
                    payload =
                        evidence.payload.copy(
                            records = BoundedProtocolList.create(evidence.payload.records.values.take(count)).proven()
                        )
                ),
                TraversalRunQualification.admitResumable(
                        limitations,
                        relationLimitations,
                        TraversalCheckpointDocument.RetainedOutput(
                            TraversalContinuationDocument.parse(token.value).proven(),
                            upstream,
                        ),
                        ReadResumeActionDocument.RESUME,
                    )
                    .proven(),
            ),
            limits,
            maximumBytes,
        )
}

private fun largestFittingTraversalPrefix(maximum: Int, encode: (Int) -> HostedResponse): Int {
    var lower = 1
    var upper = maximum
    var fitting = 0
    while (lower <= upper) {
        val count = lower + (upper - lower) / 2
        when (encode(count)) {
            is HostedResponse.Canonical<*, *, *> -> {
                fitting = count
                lower = count + 1
            }
            is HostedResponse.Oversized -> upper = count - 1
            else -> return 0
        }
    }
    return fitting
}

private val PLACEHOLDER =
    ProtocolText.parse(TraversalContinuationDocument.OUTPUT_PREFIX + "00000000-0000-0000-0000-000000000000").proven()

private fun <Value> Refinement<Value, *>.proven(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Admitted traversal output subset violated its contract")
    }
