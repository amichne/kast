package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.DiagnosticKnownCountDocument
import io.github.amichne.kast.protocol.contract.DiagnosticProgressStage
import io.github.amichne.kast.protocol.contract.DiagnosticProgressStop
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings

internal typealias HostedDiagnosticOutcome =
    OperationOutcome<DiagnosticCheckResult, DiagnosticCheckQualification, DiagnosticCheckRejection>

internal const val DIAGNOSTIC_OUTPUT_PREFIX = "diagnostic-output:v1:"

internal fun hostedDiagnosticOutputPages(limits: ReadLimits) =
    HostedOutputPages(
        CanonicalOperationWireBindings.diagnosticCheck,
        DIAGNOSTIC_OUTPUT_PREFIX,
        limits,
        normalize = { request: DiagnosticCheckRequest -> request.copy(continuation = null, executionBudget = null) },
        unavailable = DiagnosticCheckRejection.CONTINUATION_UNAVAILABLE,
        mismatch = DiagnosticCheckRejection.CONTINUATION_REQUEST_MISMATCH,
    )

internal fun HostedDiagnosticOutcome.withDiagnosticBudget(report: ExecutionBudgetReport?): HostedDiagnosticOutcome =
    when (this) {
        is OperationOutcome.Complete -> OperationOutcome.Complete(evidence.withDiagnosticBudget(report))
        is OperationOutcome.Qualified ->
            OperationOutcome.Qualified(evidence.withDiagnosticBudget(report), qualification)
        is OperationOutcome.Rejected -> this
    }

private fun EvidenceEnvelope<DiagnosticCheckResult>.withDiagnosticBudget(report: ExecutionBudgetReport?) =
    copy(payload = payload.copy(progress = payload.progress?.copy(executionBudget = report)))

/** Fit the encoded envelope, retaining the detached suffix and its original scan continuation before publication. */
internal fun encodeHostedDiagnosticResponse(
    semantic: HostedDiagnosticOutcome,
    limits: ReadLimits,
    maximumBytes: ReturnedByteLimit,
    maximumResults: ResultLimit =
        ResultLimit.parse(io.github.amichne.kast.protocol.contract.MAX_PROTOCOL_ITEMS).proven(),
    retain: (HostedDiagnosticOutcome) -> HostedOutputRetention,
): HostedResponse {
    val original =
        HostedResponse.Canonical.encode(CanonicalOperationWireBindings.diagnosticCheck, semantic, limits, maximumBytes)
    if (original is HostedResponse.EncodingRejected) return original
    val evidence =
        when (semantic) {
            is OperationOutcome.Complete -> semantic.evidence
            is OperationOutcome.Qualified -> semantic.evidence
            is OperationOutcome.Rejected -> return original
        }
    if (original !is HostedResponse.Oversized && evidence.payload.diagnostics.values.size <= maximumResults.value)
        return original
    val qualification = semantic.outputQualification(evidence)
    val fitting = DiagnosticPageEncoding(evidence, qualification, limits, maximumBytes)
    val count =
        largestFittingDiagnosticPrefix(
            minOf(evidence.payload.diagnostics.values.size - 1, maximumResults.value),
            fitting::placeholder,
        )
    if (count == 0)
        return diagnosticEncodingRejection(DiagnosticCheckRejection.OUTPUT_GRANT_TOO_SMALL, limits, maximumBytes)
    val remainder =
        evidence.copy(
            payload =
                evidence.payload.copy(
                    diagnostics = BoundedProtocolList.create(evidence.payload.diagnostics.values.drop(count)).proven()
                )
        )
    val suffix =
        when (semantic) {
            is OperationOutcome.Complete -> OperationOutcome.Complete(remainder)
            is OperationOutcome.Qualified -> OperationOutcome.Qualified(remainder, semantic.qualification)
            else -> return original
        }
    return when (val retained = retain(suffix.withDiagnosticBudget(null))) {
        is HostedOutputRetention.Retained -> fitting.encode(count, retained.token)
        HostedOutputRetention.CapacityExceeded ->
            diagnosticEncodingRejection(DiagnosticCheckRejection.CONTINUATION_CAPACITY_EXCEEDED, limits, maximumBytes)
        HostedOutputRetention.EncodingRejected -> HostedResponse.Rejected(HostedEndpointFailure.RESPONSE_REJECTED)
    }
}

private fun HostedDiagnosticOutcome.outputQualification(
    evidence: EvidenceEnvelope<DiagnosticCheckResult>
): DiagnosticCheckQualification =
    when (this) {
        is OperationOutcome.Qualified -> qualification
        else ->
            DiagnosticCheckQualification.create(
                    evidence.payload.progress?.knownDiagnosticCount
                        ?: DiagnosticKnownCountDocument.parse(evidence.payload.diagnostics.values.size).proven(),
                    true,
                    evidence.payload.progress?.analyzedFiles.orEmpty(),
                    emptyList(),
                )
                .proven()
    }

private fun diagnosticEncodingRejection(
    reason: DiagnosticCheckRejection,
    limits: ReadLimits,
    maximumBytes: ReturnedByteLimit,
) =
    HostedResponse.Canonical.encode(
        CanonicalOperationWireBindings.diagnosticCheck,
        OperationOutcome.Rejected(reason),
        limits,
        maximumBytes,
    )

private class DiagnosticPageEncoding(
    private val evidence: EvidenceEnvelope<DiagnosticCheckResult>,
    private val qualification: DiagnosticCheckQualification,
    private val limits: ReadLimits,
    private val maximumBytes: ReturnedByteLimit,
) {
    fun placeholder(count: Int) = encode(count, PLACEHOLDER)

    fun encode(count: Int, token: ProtocolText): HostedResponse =
        HostedResponse.Canonical.encode(
            CanonicalOperationWireBindings.diagnosticCheck,
            OperationOutcome.Qualified(
                evidence.copy(
                    payload =
                        evidence.payload.copy(
                            diagnostics =
                                BoundedProtocolList.create(evidence.payload.diagnostics.values.take(count)).proven(),
                            progress =
                                evidence.payload.progress?.copy(
                                    stage = DiagnosticProgressStage.OUTPUT,
                                    stop = DiagnosticProgressStop.OUTPUT_PENDING,
                                ),
                        )
                ),
                DiagnosticCheckQualification.create(
                        qualification.knownDiagnosticCount,
                        true,
                        qualification.analyzedFiles,
                        qualification.limitations,
                        token,
                    )
                    .proven(),
            ),
            limits,
            maximumBytes,
        )
}

private fun largestFittingDiagnosticPrefix(maximum: Int, encode: (Int) -> HostedResponse): Int {
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

private val PLACEHOLDER = ProtocolText.parse(DIAGNOSTIC_OUTPUT_PREFIX + "00000000-0000-0000-0000-000000000000").proven()

private fun <Value> Refinement<Value, *>.proven(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("An admitted diagnostic subset violated its contract")
    }
