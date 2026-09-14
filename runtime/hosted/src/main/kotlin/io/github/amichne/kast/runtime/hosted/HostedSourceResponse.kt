package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceEntityCountDocument
import io.github.amichne.kast.protocol.contract.SourceReadContinuationStateDocument
import io.github.amichne.kast.protocol.contract.SourceReadLimitationDocument
import io.github.amichne.kast.protocol.contract.SourceReadQualification
import io.github.amichne.kast.protocol.contract.SourceReadResult
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings

/** Response fitting retains a detached suffix before publishing a nonempty prefix. */
internal fun encodeHostedSourceResponse(
    semantic: HostedSourceOutcome,
    limits: ReadLimits,
    maximumResults: ResultLimit = ResultLimit.parse(limits[ReadLimitParameter.SEMANTIC_RESULTS].value).proven(),
    maximumBytes: ReturnedByteLimit =
        ReturnedByteLimit.parse(limits[ReadLimitParameter.HOST_RESPONSE_BYTES].value.toLong()).proven(),
    retain: (HostedSourceOutcome) -> HostedOutputRetention,
): HostedResponse {
    val original =
        HostedResponse.Canonical.encode(CanonicalOperationWireBindings.sourceRead, semantic, limits, maximumBytes)
    if (original is HostedResponse.EncodingRejected) return original
    val evidence: EvidenceEnvelope<SourceReadResult>
    val limitations: List<SourceReadLimitationDocument>
    val minimum: SourceEntityCountDocument
    when (semantic) {
        is OperationOutcome.Complete -> {
            evidence = semantic.evidence
            limitations = emptyList()
            minimum = SourceEntityCountDocument.parse(evidence.payload.entities.values.size).proven()
        }
        is OperationOutcome.Qualified -> {
            evidence = semantic.evidence
            limitations = semantic.qualification.limitations
            minimum = semantic.qualification.knownMinimumEntityCount
        }
        is OperationOutcome.Rejected -> return original
    }
    val size = evidence.payload.entities.values.size
    if (original !is HostedResponse.Oversized && size <= maximumResults.value) return original
    val exhausted = buildList {
        addAll(limitations)
        if (original is HostedResponse.Oversized) add(SourceReadLimitationDocument.RETURNED_BYTE_LIMIT_REACHED)
        if (size > maximumResults.value) add(SourceReadLimitationDocument.ENTITY_LIMIT_REACHED)
    }
        .distinct()
        .sortedBy { it.ordinal }
    val fitting = SourcePageEncoding(evidence, minimum, exhausted, limits, maximumBytes)
    val count = largestFittingSourcePrefix(minOf(size - 1, maximumResults.value), fitting::placeholder)
    if (count == 0) return original.indivisibleSource()
    val remainder =
        evidence.copy(
            payload =
                evidence.payload.copy(
                    entities = BoundedProtocolList.create(evidence.payload.entities.values.drop(count)).proven()
                )
        )
    val suffix =
        when (semantic) {
            is OperationOutcome.Complete -> OperationOutcome.Complete(remainder)
            is OperationOutcome.Qualified -> OperationOutcome.Qualified(remainder, semantic.qualification)
        }
    return retain(suffix).encodeSource { token -> fitting.encode(count, token) }
}

private fun HostedResponse.indivisibleSource(): HostedResponse =
    when (this) {
        is HostedResponse.Oversized -> this
        else -> HostedResponse.Rejected(HostedEndpointFailure.RESULT_TOO_LARGE)
    }

private fun HostedOutputRetention.encodeSource(encode: (ProtocolText) -> HostedResponse): HostedResponse =
    when (this) {
        is HostedOutputRetention.Retained -> encode(token)
        HostedOutputRetention.CapacityExceeded -> HostedResponse.Rejected(HostedEndpointFailure.RESULT_TOO_LARGE)
        HostedOutputRetention.EncodingRejected -> HostedResponse.Rejected(HostedEndpointFailure.RESPONSE_REJECTED)
    }

private class SourcePageEncoding(
    private val evidence: EvidenceEnvelope<SourceReadResult>,
    private val minimum: SourceEntityCountDocument,
    private val limitations: List<SourceReadLimitationDocument>,
    private val limits: ReadLimits,
    private val maximumBytes: ReturnedByteLimit,
) {
    fun placeholder(count: Int) = encode(count, PLACEHOLDER)

    fun encode(count: Int, token: ProtocolText): HostedResponse =
        HostedResponse.Canonical.encode(
            CanonicalOperationWireBindings.sourceRead,
            OperationOutcome.Qualified(
                evidence.copy(
                    payload =
                        evidence.payload.copy(
                            entities = BoundedProtocolList.create(evidence.payload.entities.values.take(count)).proven()
                        )
                ),
                SourceReadQualification.create(
                        minimum,
                        limitations,
                        SourceReadContinuationStateDocument.Available(token),
                    )
                    .proven(),
            ),
            limits,
            maximumBytes,
        )
}

private fun largestFittingSourcePrefix(maximum: Int, encode: (Int) -> HostedResponse): Int {
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

internal const val SOURCE_OUTPUT_PREFIX = "source-output:v1:"

private val PLACEHOLDER = ProtocolText.parse(SOURCE_OUTPUT_PREFIX + "00000000-0000-0000-0000-000000000000").proven()

private fun <Value> Refinement<Value, *>.proven(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("A subset of admitted source evidence violated its contract")
    }
