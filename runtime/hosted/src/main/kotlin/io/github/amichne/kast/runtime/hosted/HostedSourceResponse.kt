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
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.protocol.contract.SourceCheckpointDocument
import io.github.amichne.kast.protocol.contract.SourceEntityCountDocument
import io.github.amichne.kast.protocol.contract.SourcePreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.SourceQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.SourceReadLimitationDocument
import io.github.amichne.kast.protocol.contract.SourceReadQualification
import io.github.amichne.kast.protocol.contract.SourceReadResult
import io.github.amichne.kast.protocol.contract.budgetPresence
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings

/** Response fitting retains a detached suffix before publishing a nonempty prefix. */
internal fun encodeHostedSourceResponse(
    semantic: HostedSourceOutcome,
    limits: ReadLimits,
    maximumResults: ResultLimit = ResultLimit.parse(limits[ReadLimitParameter.SEMANTIC_RESULTS].value).proven(),
    maximumBytes: ReturnedByteLimit =
        ReturnedByteLimit.parse(limits[ReadLimitParameter.HOST_RESPONSE_BYTES].value.toLong()).proven(),
    retain: (HostedSourceOutcome) -> HostedOutputRetention,
): HostedResponse =
    encodeHostedSourceResponseDocument(semantic, limits, maximumResults, maximumBytes, retain)
        .withReadBudget(
            when (semantic) {
                is OperationOutcome.Complete -> semantic.evidence.payload.executionBudget.presence()
                is OperationOutcome.Qualified -> semantic.evidence.payload.executionBudget.presence()
                is OperationOutcome.Rejected -> semantic.reason.budgetPresence()
            }
        )

private fun encodeHostedSourceResponseDocument(
    semantic: HostedSourceOutcome,
    limits: ReadLimits,
    maximumResults: ResultLimit,
    maximumBytes: ReturnedByteLimit,
    retain: (HostedSourceOutcome) -> HostedOutputRetention,
): HostedResponse {
    val original =
        HostedResponse.Canonical.encode(CanonicalOperationWireBindings.sourceRead, semantic, limits, maximumBytes)
    if (original is HostedResponse.EncodingRejected) return original
    val prepared = semantic.fittingEvidence() ?: return original
    val evidence = prepared.evidence
    val limitations = prepared.limitations
    val minimum = prepared.minimum
    val size = evidence.payload.entities.values.size
    if (original !is HostedResponse.Oversized && size <= maximumResults.value) return original
    val exhausted = buildList {
        addAll(limitations)
        if (original is HostedResponse.Oversized) add(SourceReadLimitationDocument.RETURNED_BYTE_LIMIT_REACHED)
        if (size > maximumResults.value) add(SourceReadLimitationDocument.ENTITY_LIMIT_REACHED)
    }
        .distinct()
        .sortedBy { it.ordinal }
    val fitting = SourcePageEncoding(evidence, minimum, exhausted, semantic.preparedCoverage(), limits, maximumBytes)
    val count = largestFittingSourcePrefix(minOf(size - 1, maximumResults.value), fitting::placeholder)
    if (count == 0)
        return withholdIndivisibleSource(
            original,
            semantic,
            evidence,
            minimum,
            exhausted,
            limits,
            maximumResults,
            maximumBytes,
            retain,
        )
    return when (val retained = retain(semantic.sourceSuffix(count))) {
        is HostedOutputRetention.Retained -> fitting.encode(count, retained.token)
        HostedOutputRetention.CapacityExceeded -> fitting.retentionUnavailable(count)
        is HostedOutputRetention.Rejected ->
            HostedResponse.ReadRejected(
                io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryFailure.Publication(retained.cause),
                io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryStage.RESULT_DETACHED,
            )
    }
}

private data class SourceFittingEvidence(
    val evidence: EvidenceEnvelope<SourceReadResult>,
    val limitations: List<SourceReadLimitationDocument>,
    val minimum: SourceEntityCountDocument,
)

private fun HostedSourceOutcome.fittingEvidence(): SourceFittingEvidence? =
    when (this) {
        is OperationOutcome.Complete ->
            SourceFittingEvidence(
                evidence,
                emptyList(),
                SourceEntityCountDocument.parse(evidence.payload.entities.values.size).proven(),
            )
        is OperationOutcome.Qualified ->
            SourceFittingEvidence(evidence, qualification.limitations, qualification.knownMinimumEntityCount)
        is OperationOutcome.Rejected -> null
    }

private fun HostedSourceOutcome.sourceSuffix(count: Int): HostedSourceOutcome =
    when (this) {
        is OperationOutcome.Complete -> OperationOutcome.Complete(evidence.dropSourceEntities(count))
        is OperationOutcome.Qualified -> OperationOutcome.Qualified(evidence.dropSourceEntities(count), qualification)
        is OperationOutcome.Rejected -> this
    }

private fun EvidenceEnvelope<SourceReadResult>.dropSourceEntities(count: Int) =
    copy(payload = payload.copy(entities = BoundedProtocolList.create(payload.entities.values.drop(count)).proven()))

private fun withholdIndivisibleSource(
    original: HostedResponse,
    semantic: HostedSourceOutcome,
    evidence: EvidenceEnvelope<SourceReadResult>,
    minimum: SourceEntityCountDocument,
    exhausted: List<SourceReadLimitationDocument>,
    limits: ReadLimits,
    maximumResults: ResultLimit,
    maximumBytes: ReturnedByteLimit,
    retain: (HostedSourceOutcome) -> HostedOutputRetention,
): HostedResponse {
    if (evidence.payload.text is io.github.amichne.kast.protocol.contract.SourceTextProjectionDocument.Returned) {
        val withheld =
            evidence.copy(
                payload =
                    evidence.payload.copy(
                        text =
                            io.github.amichne.kast.protocol.contract.SourceTextProjectionDocument.Withheld(
                                io.github.amichne.kast.protocol.contract.SourceTextWithheldReasonDocument
                                    .BYTE_LIMIT_REACHED
                            )
                    )
            )
        val textLimitations =
            (exhausted + SourceReadLimitationDocument.TEXT_BYTE_LIMIT_REACHED).distinct().sortedBy { it.ordinal }
        val progress =
            when (semantic) {
                is OperationOutcome.Qualified -> semantic.qualification.progress
                is OperationOutcome.Rejected -> return original.indivisibleSource()
                is OperationOutcome.Complete ->
                    SourceQualifiedProgressDocument.TerminalIncomplete(
                        io.github.amichne.kast.protocol.contract.SourceTerminalReasonDocument.TEXT_PROJECTION_WITHHELD
                    )
            }
        return encodeHostedSourceResponseDocument(
            OperationOutcome.Qualified(
                withheld,
                SourceReadQualification.create(minimum, textLimitations, progress).proven(),
            ),
            limits,
            maximumResults,
            maximumBytes,
            retain,
        )
    }
    return original.indivisibleSource()
}

private fun HostedResponse.indivisibleSource(): HostedResponse =
    when (this) {
        is HostedResponse.Oversized -> this
        else -> HostedResponse.Rejected(HostedEndpointFailure.RESULT_TOO_LARGE)
    }

private class SourcePageEncoding(
    private val evidence: EvidenceEnvelope<SourceReadResult>,
    private val minimum: SourceEntityCountDocument,
    private val limitations: List<SourceReadLimitationDocument>,
    private val upstream: SourcePreparedCoverageDocument,
    private val limits: ReadLimits,
    private val maximumBytes: ReturnedByteLimit,
) {
    fun placeholder(count: Int) = encode(count, PLACEHOLDER)

    fun retentionUnavailable(count: Int): HostedResponse =
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
                        (limitations + SourceReadLimitationDocument.RETENTION_LIMIT_REACHED).distinct().sortedBy {
                            it.ordinal
                        },
                        SourceQualifiedProgressDocument.RetentionUnavailable(upstream),
                    )
                    .proven(),
            ),
            limits,
            maximumBytes,
        )

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
                        SourceQualifiedProgressDocument.Resumable(
                            SourceCheckpointDocument.RetainedOutput(token, upstream),
                            ReadResumeActionDocument.RESUME,
                        ),
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
