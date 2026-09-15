package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

data class SourceReadResult(
    val snapshot: SourceSnapshotDocument,
    val region: SourceRegionDocument,
    val entities: BoundedProtocolList<SourceEntityDocument>,
    val text: SourceTextProjectionDocument,
    val executionBudget: ExecutionBudgetReport? = null,
    val format: SourceReadFormatDocument = SourceReadFormatDocument.EXPANDED,
) : OperationResult

enum class SourceReadLimitationDocument {
    ENTITY_LIMIT_REACHED,
    TEXT_BYTE_LIMIT_REACHED,
    RETURNED_BYTE_LIMIT_REACHED,
    WORK_LIMIT_REACHED,
    TIME_LIMIT_REACHED,
    DUMB_MODE_TRANSITION,
    SEMANTIC_RESOLUTION_INCOMPLETE,
    UNSUPPORTED_ENTITY,
    PROVIDER_FAILURE,
}

enum class SourceEntityCountDocumentFailure {
    NEGATIVE
}

@JvmInline
value class SourceEntityCountDocument private constructor(val value: Int) {
    companion object {
        fun parse(raw: Int): Refinement<SourceEntityCountDocument, SourceEntityCountDocumentFailure> =
            if (raw < 0) {
                Refinement.Rejected(SourceEntityCountDocumentFailure.NEGATIVE)
            } else {
                Refinement.Refined(SourceEntityCountDocument(raw))
            }
    }
}

sealed interface SourceReadContinuationStateDocument {
    data object Unavailable : SourceReadContinuationStateDocument

    data class Available(val continuation: ProtocolText) : SourceReadContinuationStateDocument
}

enum class SourceReadQualificationFailure {
    EMPTY_LIMITATIONS,
    NON_CANONICAL_LIMITATIONS,
    CONTINUATION_REQUIRED,
    INVALID_CONTINUATION,
    UNSUPPORTED_TERMINAL_REASON,
}

@ConsistentCopyVisibility
data class SourceReadQualification
private constructor(
    val knownMinimumEntityCount: SourceEntityCountDocument,
    val limitations: List<SourceReadLimitationDocument>,
    val progress: SourceQualifiedProgressDocument,
) : OperationQualification {
    val continuation: SourceReadContinuationStateDocument
        get() =
            when (val state = progress) {
                is SourceQualifiedProgressDocument.Resumable ->
                    SourceReadContinuationStateDocument.Available(state.checkpoint.token)
                is SourceQualifiedProgressDocument.TerminalIncomplete -> SourceReadContinuationStateDocument.Unavailable
            }

    companion object {
        fun create(
            knownMinimumEntityCount: SourceEntityCountDocument,
            limitations: List<SourceReadLimitationDocument>,
            progress: SourceQualifiedProgressDocument,
        ): Refinement<SourceReadQualification, SourceReadQualificationFailure> {
            if (limitations.isEmpty()) {
                return Refinement.Rejected(SourceReadQualificationFailure.EMPTY_LIMITATIONS)
            }
            if (limitations != limitations.distinct().sortedBy { it.ordinal }) {
                return Refinement.Rejected(SourceReadQualificationFailure.NON_CANONICAL_LIMITATIONS)
            }
            if (
                SourceReadLimitationDocument.ENTITY_LIMIT_REACHED in limitations &&
                    progress is SourceQualifiedProgressDocument.TerminalIncomplete
            ) {
                return Refinement.Rejected(SourceReadQualificationFailure.CONTINUATION_REQUIRED)
            }
            if (!progress.hasCanonicalSyntax()) {
                return Refinement.Rejected(SourceReadQualificationFailure.INVALID_CONTINUATION)
            }
            if (!progress.hasSupportedTerminalReason(limitations)) {
                return Refinement.Rejected(SourceReadQualificationFailure.UNSUPPORTED_TERMINAL_REASON)
            }
            return Refinement.Refined(SourceReadQualification(knownMinimumEntityCount, limitations.toList(), progress))
        }
    }
}

@kotlinx.serialization.Serializable
enum class SourceReadRejection : SourceReadCause {
    @kotlinx.serialization.SerialName("workspace-not-ready") WORKSPACE_NOT_READY,
    @kotlinx.serialization.SerialName("workspace-root-mismatch") WORKSPACE_ROOT_MISMATCH,
    @kotlinx.serialization.SerialName("stale-generation") STALE_GENERATION,
    @kotlinx.serialization.SerialName("source-state-mismatch") SOURCE_STATE_MISMATCH,
    @kotlinx.serialization.SerialName("candidate-stale") CANDIDATE_STALE,
    @kotlinx.serialization.SerialName("source-selector-stale") SOURCE_SELECTOR_STALE,
    @kotlinx.serialization.SerialName("source-snapshot-mismatch") SOURCE_SNAPSHOT_MISMATCH,
    @kotlinx.serialization.SerialName("source-unavailable") SOURCE_UNAVAILABLE,
    @kotlinx.serialization.SerialName("document-dirty") DOCUMENT_DIRTY,
    @kotlinx.serialization.SerialName("psi-document-uncommitted") PSI_DOCUMENT_UNCOMMITTED,
    @kotlinx.serialization.SerialName("outside-source-scope") OUTSIDE_SOURCE_SCOPE,
    @kotlinx.serialization.SerialName("anchor-not-found") ANCHOR_NOT_FOUND,
    @kotlinx.serialization.SerialName("ambiguous-anchor") AMBIGUOUS_ANCHOR,
    @kotlinx.serialization.SerialName("region-not-applicable") REGION_NOT_APPLICABLE,
    @kotlinx.serialization.SerialName("region-absent") REGION_ABSENT,
    @kotlinx.serialization.SerialName("compiler-analysis-unavailable") COMPILER_ANALYSIS_UNAVAILABLE,
    @kotlinx.serialization.SerialName("contract-violation") CONTRACT_VIOLATION,
    @kotlinx.serialization.SerialName("continuation-unavailable") CONTINUATION_UNAVAILABLE,
    @kotlinx.serialization.SerialName("continuation-request-mismatch") CONTINUATION_REQUEST_MISMATCH,
}
