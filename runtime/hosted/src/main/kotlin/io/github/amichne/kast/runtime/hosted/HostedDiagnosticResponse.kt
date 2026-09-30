package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.AdmittedDiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.DiagnosticCheckFailure
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.DiagnosticKnownCountDocument
import io.github.amichne.kast.protocol.contract.DiagnosticProgressStage
import io.github.amichne.kast.protocol.contract.DiagnosticProgressStop
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.budgetPresence
import io.github.amichne.kast.protocol.contract.reason
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings

internal typealias HostedDiagnosticOutcome =
    OperationOutcome<DiagnosticCheckResult, DiagnosticCheckQualification, DiagnosticCheckFailure>

internal const val DIAGNOSTIC_OUTPUT_PREFIX = "diagnostic-output:v1:"

internal fun HostedDiagnosticOutcome.withDiagnosticBudget(report: ExecutionBudgetReport?): HostedDiagnosticOutcome =
    when (this) {
        is OperationOutcome.Complete -> OperationOutcome.Complete(evidence.withDiagnosticBudget(report))
        is OperationOutcome.Qualified ->
            OperationOutcome.Qualified(evidence.withDiagnosticBudget(report), qualification)
        is OperationOutcome.Rejected ->
            OperationOutcome.Rejected(
                if (report == null) reason.reason() else AdmittedDiagnosticCheckRejection(reason.reason(), report)
            )
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
    retain: (HostedDiagnosticOutcome) -> Refinement<ProtocolText, DiagnosticCheckRejection>,
): HostedResponse =
    encodeHostedDiagnosticDocument(semantic, limits, maximumBytes, maximumResults, retain)
        .withReadBudget(
            when (semantic) {
                is OperationOutcome.Complete -> semantic.evidence.payload.progress?.executionBudget.presence()
                is OperationOutcome.Qualified -> semantic.evidence.payload.progress?.executionBudget.presence()
                is OperationOutcome.Rejected -> semantic.reason.budgetPresence()
            }
        )

private fun encodeHostedDiagnosticDocument(
    semantic: HostedDiagnosticOutcome,
    limits: ReadLimits,
    maximumBytes: ReturnedByteLimit,
    maximumResults: ResultLimit,
    retain: (HostedDiagnosticOutcome) -> Refinement<ProtocolText, DiagnosticCheckRejection>,
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
    val fitting = DiagnosticPageEncoding(evidence, semantic.outputQualification(evidence), limits, maximumBytes)
    val count =
        largestFittingDiagnosticPrefix(
            minOf(evidence.payload.diagnostics.values.size - 1, maximumResults.value),
            fitting::placeholder,
        )
    if (count == 0)
        return diagnosticEncodingRejection(
            DiagnosticCheckRejection.OUTPUT_GRANT_TOO_SMALL,
            evidence.payload.progress?.executionBudget,
            limits,
            maximumBytes,
        )
    val suffix = semantic.diagnosticSuffix(count)
    return when (val retained = retain(suffix.withDiagnosticBudget(null))) {
        is Refinement.Refined -> fitting.encode(count, retained.value)
        is Refinement.Rejected ->
            if (retained.failure == DiagnosticCheckRejection.CONTINUATION_CAPACITY_EXCEEDED)
                fitting.retentionUnavailable(count)
            else
                diagnosticEncodingRejection(
                    retained.failure,
                    evidence.payload.progress?.executionBudget,
                    limits,
                    maximumBytes,
                )
    }
}

private fun HostedDiagnosticOutcome.diagnosticSuffix(count: Int): HostedDiagnosticOutcome {
    fun EvidenceEnvelope<DiagnosticCheckResult>.dropFacts() =
        copy(
            payload =
                payload.copy(diagnostics = BoundedProtocolList.create(payload.diagnostics.values.drop(count)).proven())
        )
    return when (this) {
        is OperationOutcome.Complete -> OperationOutcome.Complete(evidence.dropFacts())
        is OperationOutcome.Qualified -> OperationOutcome.Qualified(evidence.dropFacts(), qualification)
        is OperationOutcome.Rejected -> this
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
    report: ExecutionBudgetReport?,
    limits: ReadLimits,
    maximumBytes: ReturnedByteLimit,
) =
    HostedResponse.Canonical.encode(
        CanonicalOperationWireBindings.diagnosticCheck,
        OperationOutcome.Rejected(reason).withDiagnosticBudget(report),
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

    fun retentionUnavailable(count: Int): HostedResponse =
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
                                    stage = DiagnosticProgressStage.FINISHED,
                                    stop = DiagnosticProgressStop.RETENTION_CAPACITY_EXCEEDED,
                                ),
                        )
                ),
                DiagnosticCheckQualification.create(
                        qualification.knownDiagnosticCount,
                        true,
                        qualification.analyzedFiles,
                        qualification.limitations,
                        retentionFailure =
                            io.github.amichne.kast.protocol.contract.DiagnosticRetentionFailureDocument
                                .CAPACITY_EXCEEDED,
                    )
                    .proven(),
            ),
            limits,
            maximumBytes,
        )

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
                        qualification.retentionFailure,
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
