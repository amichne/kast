package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.diagnostic.contract.DiagnosticScanRequest
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeQuery
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RequestedExecutionBudget
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.DiagnosticCheckFailure
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.reason
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

typealias DiagnosticPublishedPage =
    OperationOutcome<DiagnosticCheckResult, DiagnosticCheckQualification, DiagnosticCheckFailure>

/** Unforgeable request-local ownership of the existing diagnostic store. */
class DiagnosticExecutionClaim internal constructor()

internal sealed interface DiagnosticNextPage {
    data object Terminal : DiagnosticNextPage

    data object RetentionUnavailable : DiagnosticNextPage

    data class Continue(val token: ProtocolText) : DiagnosticNextPage
}

internal data class DiagnosticStoredPage(val result: DiagnosticScanResult, val next: DiagnosticNextPage)

internal sealed interface DiagnosticCheckpointAdmission {
    data class Execute(val request: DiagnosticScanRequest, val claim: DiagnosticExecutionClaim) :
        DiagnosticCheckpointAdmission

    data class Outcome(val page: DiagnosticPublishedPage, val claim: DiagnosticExecutionClaim) :
        DiagnosticCheckpointAdmission

    data class Rejected(val reason: DiagnosticCheckRejection) : DiagnosticCheckpointAdmission
}

/** The admitted scope proof remains owned by the request identity; equality compares its canonical facts. */
internal class DiagnosticReplayKey(
    val query: DiagnosticScopeQuery,
    val limit: ProtocolCount,
    val grant: RequestedExecutionBudget,
) {
    override fun equals(other: Any?): Boolean =
        other is DiagnosticReplayKey &&
            query.path == other.query.path &&
            query.lease == other.query.lease &&
            limit == other.limit &&
            grant == other.grant

    override fun hashCode(): Int =
        (((query.path.hashCode() * 31 + query.lease.hashCode()) * 31 + limit.hashCode()) * 31 + grant.hashCode())
}

fun DiagnosticPublishedPage.publicationPage(): DiagnosticPublishedPage =
    when (this) {
        is OperationOutcome.Complete ->
            OperationOutcome.Complete(
                evidence.copy(
                    payload = evidence.payload.copy(progress = evidence.payload.progress?.copy(executionBudget = null))
                )
            )
        is OperationOutcome.Qualified ->
            OperationOutcome.Qualified(
                evidence.copy(
                    payload = evidence.payload.copy(progress = evidence.payload.progress?.copy(executionBudget = null))
                ),
                qualification,
            )
        is OperationOutcome.Rejected -> OperationOutcome.Rejected(reason.reason())
    }

internal fun DiagnosticPublishedPage.matches(query: DiagnosticScopeQuery): Boolean =
    when (this) {
        is OperationOutcome.Complete ->
            evidence.operation == CanonicalOperation.DIAGNOSTIC_CHECK.id &&
                evidence.basis == query.lease.evidenceBasis()
        is OperationOutcome.Qualified ->
            evidence.operation == CanonicalOperation.DIAGNOSTIC_CHECK.id &&
                evidence.basis == query.lease.evidenceBasis()
        is OperationOutcome.Rejected -> true
    }

internal fun DiagnosticPublishedPage.factCount(): Int =
    when (this) {
        is OperationOutcome.Complete -> evidence.payload.diagnostics.values.size
        is OperationOutcome.Qualified -> evidence.payload.diagnostics.values.size
        is OperationOutcome.Rejected -> 0
    }

internal fun DiagnosticPublishedPage.continuation(): ProtocolText? =
    (this as? OperationOutcome.Qualified)?.qualification?.continuation

internal fun DiagnosticPublishedPage.diagnosticRetainedBytes(): Long {
    val payload =
        when (this) {
            is OperationOutcome.Complete -> evidence.payload
            is OperationOutcome.Qualified -> evidence.payload
            is OperationOutcome.Rejected -> return DIAGNOSTIC_ENTRY_OVERHEAD
        }
    return DIAGNOSTIC_ENTRY_OVERHEAD +
        payload.diagnostics.values.sumOf {
            DIAGNOSTIC_FACT_OVERHEAD +
                (it.message.value.length.toLong() +
                    it.code.value.length +
                    it.location.file.value.length +
                    it.location.candidateSelector.value.length) * DIAGNOSTIC_CHARACTER_BYTES
        } +
        payload.progress?.analyzedFiles.orEmpty().sumOf {
            DIAGNOSTIC_ENTRY_OVERHEAD + it.value.length * DIAGNOSTIC_CHARACTER_BYTES
        } +
        payload.progress?.requestedPath?.value.orEmpty().length * DIAGNOSTIC_CHARACTER_BYTES +
        if (this is OperationOutcome.Qualified)
            qualification.limitations.sumOf {
                DIAGNOSTIC_ENTRY_OVERHEAD + it.file.value.length * DIAGNOSTIC_CHARACTER_BYTES
            }
        else 0L
}

internal fun diagnosticMatchingQuery(
    current: DiagnosticScopeQuery,
    original: DiagnosticScopeQuery,
    currentLimit: ProtocolCount,
    originalLimit: ProtocolCount,
): Refinement<Unit, DiagnosticCheckRejection> =
    when {
        current.lease != original.lease -> Refinement.Rejected(DiagnosticCheckRejection.STALE_CONTINUATION)
        current.path != original.path || currentLimit != originalLimit ->
            Refinement.Rejected(DiagnosticCheckRejection.CONTINUATION_REQUEST_MISMATCH)
        else -> Refinement.Refined(Unit)
    }

internal fun diagnosticCapacityRejected(): Refinement.Rejected<DiagnosticCheckRejection> =
    Refinement.Rejected(DiagnosticCheckRejection.CONTINUATION_CAPACITY_EXCEEDED)

internal const val DIAGNOSTIC_ENTRY_OVERHEAD = 512L
internal const val DIAGNOSTIC_FACT_OVERHEAD = 2048L
internal const val DIAGNOSTIC_CHARACTER_BYTES = 4L

internal fun DiagnosticScopeQuery.diagnosticRetainedIdentityBytes(): Long =
    DIAGNOSTIC_ENTRY_OVERHEAD +
        path.toString().length * DIAGNOSTIC_CHARACTER_BYTES +
        lease.diagnosticRetainedIdentityBytes()

internal fun SemanticReadAuthority.diagnosticRetainedIdentityBytes(): Long =
    DIAGNOSTIC_ENTRY_OVERHEAD +
        (workspaceRoot.value.length.toLong() + identity.revisionKey.value.length) * DIAGNOSTIC_CHARACTER_BYTES

/** The final transition retains the precise reason that detached publication cannot proceed. */
enum class DiagnosticPublicationFailure {
    OWNER_RETIRED,
    CLAIM_UNAVAILABLE,
    EXPIRED,
    DEPENDENCY_UNAVAILABLE,
    PUBLISHED_PAGE_MISMATCH,
    NON_ADVANCING_SUCCESSOR,
    INVALID_FITTED_PAGE,
    CAPACITY_EXCEEDED,
}

internal fun DiagnosticPublicationFailure.rejection(): DiagnosticCheckRejection =
    when (this) {
        DiagnosticPublicationFailure.OWNER_RETIRED -> DiagnosticCheckRejection.PUBLICATION_OWNER_RETIRED
        DiagnosticPublicationFailure.CLAIM_UNAVAILABLE -> DiagnosticCheckRejection.PUBLICATION_CLAIM_UNAVAILABLE
        DiagnosticPublicationFailure.EXPIRED -> DiagnosticCheckRejection.PUBLICATION_EXPIRED
        DiagnosticPublicationFailure.DEPENDENCY_UNAVAILABLE ->
            DiagnosticCheckRejection.PUBLICATION_DEPENDENCY_UNAVAILABLE
        DiagnosticPublicationFailure.PUBLISHED_PAGE_MISMATCH -> DiagnosticCheckRejection.PUBLICATION_PAGE_MISMATCH
        DiagnosticPublicationFailure.NON_ADVANCING_SUCCESSOR -> DiagnosticCheckRejection.PUBLICATION_NON_ADVANCING
        DiagnosticPublicationFailure.INVALID_FITTED_PAGE -> DiagnosticCheckRejection.PUBLICATION_INVALID_FITTED_PAGE
        DiagnosticPublicationFailure.CAPACITY_EXCEEDED -> DiagnosticCheckRejection.CONTINUATION_CAPACITY_EXCEEDED
    }
