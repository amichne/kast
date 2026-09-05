package io.github.amichne.kast.source.contract

import io.github.amichne.kast.kernel.Refinement

enum class SourceTextProjectionFailure {
    TEXT_LENGTH_MISMATCH,
    TEXT_NOT_NORMALIZED,
    DOCUMENT_IDENTITY_MISMATCH,
}

enum class SourceTextWithheldReason {
    BYTE_LIMIT_REACHED,
    PROVIDER_UNAVAILABLE,
}

/** Exact text state for one selected region; absence is always explicit. */
sealed interface SourceTextProjection {
    data object NotRequested : SourceTextProjection

    class Returned private constructor(
        val selector: SourceSelector,
        val text: String,
        val lines: SourceLineRange,
    ) : SourceTextProjection {
        companion object {
            internal fun create(selector: SourceSelector, text: String, lines: SourceLineRange): Returned =
                Returned(selector, text, lines)
        }
    }

    data class Withheld(val reason: SourceTextWithheldReason) : SourceTextProjection

    companion object {
        /**
         * Refines the full normalized committed document into exact selected text and one-based
         * source lines. Length and digest must match the selector snapshot before extraction;
         * [SourceTextProjectionFailure] closes every expected mismatch.
         */
        fun returned(
            selector: SourceSelector,
            normalizedDocumentText: String,
        ): Refinement<Returned, SourceTextProjectionFailure> {
            if ('\r' in normalizedDocumentText) {
                return Refinement.Rejected(SourceTextProjectionFailure.TEXT_NOT_NORMALIZED)
            }
            if (normalizedDocumentText.length != selector.snapshot.length.value) {
                return Refinement.Rejected(SourceTextProjectionFailure.TEXT_LENGTH_MISMATCH)
            }
            if (
                SourceTextIdentity.fromNormalizedCommittedText(normalizedDocumentText) != selector.snapshot.textIdentity
            ) {
                return Refinement.Rejected(SourceTextProjectionFailure.DOCUMENT_IDENTITY_MISMATCH)
            }
            return Refinement.Refined(Returned.create(
                selector,
                normalizedDocumentText.substring(selector.range.startInclusive.value, selector.range.endExclusive.value),
                SourceLineRange.fromCommittedText(normalizedDocumentText, selector.range),
            ))
        }
    }
}

enum class SourceEntityCountFailure {
    NEGATIVE,
}

@JvmInline
value class SourceEntityCount private constructor(val value: Int) {
    companion object {
        fun parse(raw: Int): Refinement<SourceEntityCount, SourceEntityCountFailure> =
            if (raw < 0) {
                Refinement.Rejected(SourceEntityCountFailure.NEGATIVE)
            } else {
                Refinement.Refined(SourceEntityCount(raw))
            }
    }
}

enum class SourceReadLimitation {
    ENTITY_LIMIT_REACHED,
    TEXT_BYTE_LIMIT_REACHED,
    WORK_LIMIT_REACHED,
    TIME_LIMIT_REACHED,
    DUMB_MODE_TRANSITION,
    SEMANTIC_RESOLUTION_INCOMPLETE,
    UNSUPPORTED_ENTITY,
    PROVIDER_FAILURE,
}

sealed interface SourceReadContinuationState {
    data object Unavailable : SourceReadContinuationState
    data class Available(val continuation: SourceReadContinuation) : SourceReadContinuationState
}

enum class SourceReadQualificationFailure {
    EMPTY_LIMITATIONS,
    CONTINUATION_REQUIRED,
}

/** Exact known-minimum coverage, canonical limitations, and explicit resumability. */
class SourceReadQualification private constructor(
    val knownMinimumEntityCount: SourceEntityCount,
    val limitations: List<SourceReadLimitation>,
    val continuation: SourceReadContinuationState,
) {
    companion object {
        fun create(
            knownMinimumEntityCount: SourceEntityCount,
            limitations: Set<SourceReadLimitation>,
            continuation: SourceReadContinuationState,
        ): Refinement<SourceReadQualification, SourceReadQualificationFailure> {
            if (limitations.isEmpty()) {
                return Refinement.Rejected(SourceReadQualificationFailure.EMPTY_LIMITATIONS)
            }
            if (
                SourceReadLimitation.ENTITY_LIMIT_REACHED in limitations &&
                continuation is SourceReadContinuationState.Unavailable
            ) {
                return Refinement.Rejected(SourceReadQualificationFailure.CONTINUATION_REQUIRED)
            }
            return Refinement.Refined(
                SourceReadQualification(
                    knownMinimumEntityCount,
                    limitations.sortedBy { it.ordinal },
                    continuation,
                ),
            )
        }
    }
}

enum class SourceReadRejection {
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
}

enum class SourceReadEvidenceFailure {
    REGION_SNAPSHOT_MISMATCH,
    ENTITY_SNAPSHOT_MISMATCH,
    ENTITY_OUTSIDE_REGION,
    TEXT_SNAPSHOT_MISMATCH,
    TEXT_OUTSIDE_REGION,
    WITHHELD_TEXT_IS_NOT_COMPLETE,
}

sealed interface SourceReadResult {
    @ConsistentCopyVisibility
    data class Complete private constructor(
        val snapshot: SourceSnapshot,
        val region: SourceRegion,
        val entities: List<SourceEntity>,
        val text: SourceTextProjection,
    ) : SourceReadResult {
        companion object {
            fun create(
                snapshot: SourceSnapshot,
                region: SourceRegion,
                entities: List<SourceEntity>,
                text: SourceTextProjection,
            ): Refinement<Complete, SourceReadEvidenceFailure> = when (
                val admitted = admitEvidence(snapshot, region, entities, text, complete = true)
            ) {
                is Refinement.Refined -> Refinement.Refined(
                    Complete(snapshot, region, admitted.value, text),
                )
                is Refinement.Rejected -> admitted
            }
        }
    }

    @ConsistentCopyVisibility
    data class Qualified private constructor(
        val snapshot: SourceSnapshot,
        val region: SourceRegion,
        val entities: List<SourceEntity>,
        val text: SourceTextProjection,
        val qualification: SourceReadQualification,
    ) : SourceReadResult {
        companion object {
            fun create(
                snapshot: SourceSnapshot,
                region: SourceRegion,
                entities: List<SourceEntity>,
                text: SourceTextProjection,
                qualification: SourceReadQualification,
            ): Refinement<Qualified, SourceReadEvidenceFailure> = when (
                val admitted = admitEvidence(snapshot, region, entities, text, complete = false)
            ) {
                is Refinement.Refined -> Refinement.Refined(
                    Qualified(snapshot, region, admitted.value, text, qualification),
                )
                is Refinement.Rejected -> admitted
            }
        }
    }

    data class Rejected(val reason: SourceReadRejection) : SourceReadResult
}

private fun admitEvidence(
    snapshot: SourceSnapshot,
    region: SourceRegion,
    entities: List<SourceEntity>,
    text: SourceTextProjection,
    complete: Boolean,
): Refinement<List<SourceEntity>, SourceReadEvidenceFailure> {
    if (region.selector.snapshot != snapshot) {
        return Refinement.Rejected(SourceReadEvidenceFailure.REGION_SNAPSHOT_MISMATCH)
    }
    for (entity in entities) {
        if (entity.selector.snapshot != snapshot) {
            return Refinement.Rejected(SourceReadEvidenceFailure.ENTITY_SNAPSHOT_MISMATCH)
        }
        if (!region.selector.range.contains(entity.selector.range)) {
            return Refinement.Rejected(SourceReadEvidenceFailure.ENTITY_OUTSIDE_REGION)
        }
    }
    when (text) {
        SourceTextProjection.NotRequested -> Unit
        is SourceTextProjection.Withheld -> if (complete) {
            return Refinement.Rejected(SourceReadEvidenceFailure.WITHHELD_TEXT_IS_NOT_COMPLETE)
        }
        is SourceTextProjection.Returned -> {
            if (text.selector.snapshot != snapshot) {
                return Refinement.Rejected(SourceReadEvidenceFailure.TEXT_SNAPSHOT_MISMATCH)
            }
            if (!region.selector.range.contains(text.selector.range)) {
                return Refinement.Rejected(SourceReadEvidenceFailure.TEXT_OUTSIDE_REGION)
            }
        }
    }
    return Refinement.Refined(java.util.List.copyOf(entities))
}

private fun SourceRange.contains(other: SourceRange): Boolean =
    snapshot == other.snapshot &&
        startInclusive <= other.startInclusive &&
        endExclusive >= other.endExclusive
