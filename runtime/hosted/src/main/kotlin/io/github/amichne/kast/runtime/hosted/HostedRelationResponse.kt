package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.protocol.contract.RelationCheckpointDocument
import io.github.amichne.kast.protocol.contract.RelationContinuationDocument
import io.github.amichne.kast.protocol.contract.RelationKnownMinimumDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationPreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.RelationReadFailure
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.contract.budgetPresence
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings

internal typealias HostedRelationOutcome =
    OperationOutcome<RelationReadResult, RelationReadQualification, RelationReadFailure>

/** Response fitting retains a detached suffix before publishing a nonempty prefix. */
internal fun encodeHostedRelationResponse(
    semantic: HostedRelationOutcome,
    limits: ReadLimits,
    maximumResults: ResultLimit = ResultLimit.parse(limits[ReadLimitParameter.SEMANTIC_RESULTS].value).proven(),
    maximumBytes: ReturnedByteLimit =
        ReturnedByteLimit.parse(limits[ReadLimitParameter.HOST_RESPONSE_BYTES].value.toLong()).proven(),
    retain: (HostedRelationOutcome) -> HostedOutputRetention,
): HostedResponse =
    encodeHostedRelationResponseDocument(semantic, limits, maximumResults, maximumBytes, retain)
        .withReadBudget(
            when (semantic) {
                is OperationOutcome.Complete -> semantic.evidence.payload.executionBudget.presence()
                is OperationOutcome.Qualified -> semantic.evidence.payload.executionBudget.presence()
                is OperationOutcome.Rejected -> semantic.reason.budgetPresence()
            }
        )

private fun encodeHostedRelationResponseDocument(
    semantic: HostedRelationOutcome,
    limits: ReadLimits,
    maximumResults: ResultLimit,
    maximumBytes: ReturnedByteLimit,
    retain: (HostedRelationOutcome) -> HostedOutputRetention,
): HostedResponse {
    val original =
        HostedResponse.Canonical.encode(CanonicalOperationWireBindings.relationRead, semantic, limits, maximumBytes)
    if (original is HostedResponse.EncodingRejected) return original
    val evidence: EvidenceEnvelope<RelationReadResult>
    val limitations: List<RelationLimitationDocument>
    val minimum: RelationKnownMinimumDocument
    when (semantic) {
        is OperationOutcome.Complete -> {
            evidence = semantic.evidence
            limitations = emptyList()
            minimum = RelationKnownMinimumDocument.parse(evidence.payload.relations.values.size).proven()
        }
        is OperationOutcome.Qualified -> {
            evidence = semantic.evidence
            limitations = semantic.qualification.limitations
            minimum = semantic.qualification.knownMinimum
        }
        is OperationOutcome.Rejected -> return original
    }
    val size = evidence.payload.relations.values.size
    if (original !is HostedResponse.Oversized && size <= maximumResults.value) return original
    val exhausted = buildList {
        addAll(limitations)
        if (original is HostedResponse.Oversized) add(RelationLimitationDocument.BYTE_LIMIT_REACHED)
        if (size > maximumResults.value) add(RelationLimitationDocument.RESULT_LIMIT_REACHED)
    }
        .distinct()
        .sortedBy { it.ordinal }
    val fitting =
        RelationPageEncoding(evidence, minimum, exhausted, semantic.preparedRelationCoverage(), limits, maximumBytes)
    val count = largestFittingRelationPrefix(minOf(size - 1, maximumResults.value), fitting::placeholder)
    if (count == 0) return original.indivisibleRelation()
    val remainder =
        evidence.copy(
            payload =
                evidence.payload.copy(
                    relations = BoundedProtocolList.create(evidence.payload.relations.values.drop(count)).proven()
                )
        )
    val suffix =
        when (semantic) {
            is OperationOutcome.Complete -> OperationOutcome.Complete(remainder)
            is OperationOutcome.Qualified -> OperationOutcome.Qualified(remainder, semantic.qualification)
            else -> return original
        }
    return retain(suffix).encodeRelation { token -> fitting.encode(count, token) }
}

private fun HostedResponse.indivisibleRelation(): HostedResponse =
    when (this) {
        is HostedResponse.Oversized -> this
        else -> HostedResponse.Rejected(HostedEndpointFailure.RESULT_TOO_LARGE)
    }

private fun HostedOutputRetention.encodeRelation(
    encode: (RelationContinuationDocument) -> HostedResponse
): HostedResponse =
    when (this) {
        is HostedOutputRetention.Retained ->
            when (val parsed = RelationContinuationDocument.parse(token.value)) {
                is Refinement.Refined -> encode(parsed.value)
                is Refinement.Rejected -> HostedResponse.Rejected(HostedEndpointFailure.RESPONSE_REJECTED)
            }
        HostedOutputRetention.CapacityExceeded -> HostedResponse.Rejected(HostedEndpointFailure.RESULT_TOO_LARGE)
        HostedOutputRetention.EncodingRejected -> HostedResponse.Rejected(HostedEndpointFailure.RESPONSE_REJECTED)
    }

private class RelationPageEncoding(
    private val evidence: EvidenceEnvelope<RelationReadResult>,
    private val minimum: RelationKnownMinimumDocument,
    private val limitations: List<RelationLimitationDocument>,
    private val upstream: RelationPreparedCoverageDocument,
    private val limits: ReadLimits,
    private val maximumBytes: ReturnedByteLimit,
) {
    fun placeholder(count: Int) = encode(count, PLACEHOLDER)

    fun encode(count: Int, token: RelationContinuationDocument): HostedResponse =
        HostedResponse.Canonical.encode(
            CanonicalOperationWireBindings.relationRead,
            OperationOutcome.Qualified(
                evidence.copy(
                    payload =
                        evidence.payload.copy(
                            relations =
                                BoundedProtocolList.create(evidence.payload.relations.values.take(count)).proven()
                        )
                ),
                RelationReadQualification.admitResumable(
                        knownMinimum = minimum,
                        limitations = limitations,
                        checkpoint = RelationCheckpointDocument.RetainedOutput(token, upstream),
                        nextAction = ReadResumeActionDocument.RESUME,
                    )
                    .proven(),
            ),
            limits,
            maximumBytes,
        )
}

private fun largestFittingRelationPrefix(maximum: Int, encode: (Int) -> HostedResponse): Int {
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
    RelationContinuationDocument.parse(
            RelationContinuationDocument.OUTPUT_PREFIX + "00000000-0000-0000-0000-000000000000"
        )
        .proven()

private fun <Value> Refinement<Value, *>.proven(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("A subset of admitted relation evidence violated its contract")
    }
