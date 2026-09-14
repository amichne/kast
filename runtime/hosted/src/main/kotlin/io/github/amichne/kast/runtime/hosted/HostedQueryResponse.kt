package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination

/** Actual encoded-byte pagination retains the entire suffix before publishing any prefix. */
internal fun encodeHostedQueryResponse(
    semantic: HostedQueryOutcome,
    limits: ReadLimits = ReadLimits.Default,
    observation: IntellijReadObservation = IntellijReadObservation.None,
    retain: ((HostedQueryOutcome) -> HostedOutputRetention)? = null,
): HostedResponse {
    val original = HostedResponse.Canonical.encode(CanonicalOperationWireBindings.queryRun, semantic, limits)
    if (original !is HostedResponse.Oversized) return original
    observation.terminated(IntellijReadTermination.RESPONSE_BYTE_LIMIT)
    // Without a continuation owner no prefix may be irreversibly published.
    if (retain == null) return original
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
    val qualification =
        QueryRunQualification.create(
                minimum,
                (limitations + QueryLimitationDocument.BYTE_LIMIT_REACHED).distinct().sortedBy { it.ordinal },
            )
            .proven()
    val placeholder = ProtocolText.parse(HostedQueryContinuations.prefix + "0".repeat(36)).proven()
    fun encode(count: Int, token: ProtocolText): HostedResponse =
        HostedResponse.Canonical.encode(
            CanonicalOperationWireBindings.queryRun,
            OperationOutcome.Qualified(
                evidence.copy(
                    payload =
                        evidence.payload.copy(
                            items = BoundedProtocolList.create(evidence.payload.items.values.take(count)).proven(),
                            continuation = token,
                            terminalReason = null,
                        )
                ),
                qualification,
            ),
            limits,
        )
    val bestCount = largestHostedQueryPrefix(evidence.payload.items.values.size) { count -> encode(count, placeholder) }
    // An empty prefix cannot advance a byte-bound continuation. Fail with finite rejection instead.
    if (bestCount == 0) return original
    val remainingEvidence =
        evidence.copy(
            payload =
                evidence.payload.copy(
                    items = BoundedProtocolList.create(evidence.payload.items.values.drop(bestCount)).proven(),
                    failures = BoundedProtocolList.create(emptyList<QueryItemFailureDocument>()).proven(),
                )
        )
    val remaining =
        when (semantic) {
            is OperationOutcome.Complete -> OperationOutcome.Complete(remainingEvidence)
            is OperationOutcome.Qualified -> OperationOutcome.Qualified(remainingEvidence, semantic.qualification)
            else -> return original
        }
    return retain(remaining).encodeOr(original) { token -> encode(bestCount, token) }
}

private fun <Value, Failure> Refinement<Value, Failure>.proven(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("A subset of an admitted query result violated its contract")
    }

private fun largestHostedQueryPrefix(itemCount: Int, encode: (Int) -> HostedResponse): Int {
    var bestCount = 0
    var lower = 1
    var upper = itemCount - 1
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
