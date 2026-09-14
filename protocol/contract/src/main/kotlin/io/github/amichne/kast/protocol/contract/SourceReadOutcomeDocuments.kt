package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

data class SourceReadResult(
    val snapshot: SourceSnapshotDocument,
    val region: SourceRegionDocument,
    val entities: BoundedProtocolList<SourceEntityDocument>,
    val text: SourceTextProjectionDocument,
    val executionBudget: ExecutionBudgetReport? = null,
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

enum class SourceReadRejection : SourceReadFailure {
    WORKSPACE_NOT_READY,
    WORKSPACE_ROOT_MISMATCH,
    STALE_GENERATION,
    SOURCE_STATE_MISMATCH,
    CANDIDATE_STALE,
    SOURCE_SELECTOR_STALE,
    SOURCE_SNAPSHOT_MISMATCH,
    SOURCE_UNAVAILABLE,
    DOCUMENT_DIRTY,
    PSI_DOCUMENT_UNCOMMITTED,
    OUTSIDE_SOURCE_SCOPE,
    ANCHOR_NOT_FOUND,
    AMBIGUOUS_ANCHOR,
    REGION_NOT_APPLICABLE,
    REGION_ABSENT,
    COMPILER_ANALYSIS_UNAVAILABLE,
    CONTRACT_VIOLATION,
    CONTINUATION_UNAVAILABLE,
    CONTINUATION_REQUEST_MISMATCH,
}
