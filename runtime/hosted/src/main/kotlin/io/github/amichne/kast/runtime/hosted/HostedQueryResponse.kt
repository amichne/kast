package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination

/** Bound the real encoded bytes, including opaque references, without discarding failure or coverage evidence. */
internal fun encodeHostedQueryResponse(
    semantic: OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunRejection>,
    limits: ReadLimits = ReadLimits.Default,
    observation: IntellijReadObservation = IntellijReadObservation.None,
): HostedResponse {
    val original = HostedResponse.Canonical.encode(CanonicalOperationWireBindings.queryRun, semantic, limits)
    if (original !is HostedResponse.Oversized) return original
    observation.terminated(IntellijReadTermination.RESPONSE_BYTE_LIMIT)
    val projection =
        when (semantic) {
            is OperationOutcome.Complete ->
                BoundedHostedQueryProjection(
                    semantic.evidence,
                    QueryKnownMinimum.parse(semantic.evidence.payload.items.values.size).proven(),
                    emptyList(),
                )
            is OperationOutcome.Qualified ->
                BoundedHostedQueryProjection(
                    semantic.evidence,
                    semantic.qualification.knownMinimum,
                    semantic.qualification.limitations,
                )
            is OperationOutcome.Rejected -> return original
        }
    // Mandatory envelope and failures must fit. Never erase them to manufacture a successful response.
    var best = projection.encode(0, limits)
    if (best !is HostedResponse.Canonical<*, *, *>) return best
    var lower = 1
    var upper = projection.itemCount - 1
    while (lower <= upper) {
        val count = lower + (upper - lower) / 2
        when (val candidate = projection.encode(count, limits)) {
            is HostedResponse.Canonical<*, *, *> -> {
                best = candidate
                lower = count + 1
            }
            is HostedResponse.Oversized -> upper = count - 1
            else -> return candidate
        }
    }
    return best
}

private class BoundedHostedQueryProjection(
    private val evidence: EvidenceEnvelope<QueryRunResult>,
    knownMinimum: QueryKnownMinimum,
    limitations: List<QueryLimitationDocument>,
) {
    val itemCount = evidence.payload.items.values.size
    // The semantic evaluator already proved this lower bound; projection cannot weaken it.
    private val qualification =
        QueryRunQualification.create(
                knownMinimum,
                (limitations + QueryLimitationDocument.BYTE_LIMIT_REACHED).distinct().sortedBy { it.ordinal },
            )
            .proven()

    fun encode(count: Int, limits: ReadLimits): HostedResponse =
        HostedResponse.Canonical.encode(
            CanonicalOperationWireBindings.queryRun,
            OperationOutcome.Qualified(
                evidence.copy(
                    payload =
                        evidence.payload.copy(
                            items = BoundedProtocolList.create(evidence.payload.items.values.take(count)).proven()
                        )
                ),
                qualification,
            ),
            limits,
        )
}

private fun <Value, Failure> Refinement<Value, Failure>.proven(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("A subset of an admitted query result violated its contract")
    }
