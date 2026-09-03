package io.github.amichne.kast.source.contract

import io.github.amichne.kast.kernel.Refinement

enum class SourceRangeFailure {
    REVERSED,
    OUTSIDE_SNAPSHOT,
}

/** One possibly empty half-open UTF-16 range inseparable from its exact source snapshot. */
@ConsistentCopyVisibility
data class SourceRange private constructor(
    val snapshot: SourceSnapshot,
    val startInclusive: Utf16CodeUnitOffset,
    val endExclusive: Utf16CodeUnitOffset,
) {
    companion object {
        /**
         * Proof transition: `(SourceSnapshot, Utf16CodeUnitOffset, Utf16CodeUnitOffset) ->
         * Refinement<SourceRange, SourceRangeFailure>`.
         *
         * Establishes an ordered half-open interval fully contained by the snapshot's normalized
         * document length. Empty ranges remain valid so an empty complete file is representable.
         * [SourceRangeFailure] is the closed expected failure.
         */
        fun create(
            snapshot: SourceSnapshot,
            startInclusive: Utf16CodeUnitOffset,
            endExclusive: Utf16CodeUnitOffset,
        ): Refinement<SourceRange, SourceRangeFailure> = when {
            endExclusive < startInclusive -> Refinement.Rejected(SourceRangeFailure.REVERSED)
            endExclusive.value > snapshot.length.value ->
                Refinement.Rejected(SourceRangeFailure.OUTSIDE_SNAPSHOT)
            else -> Refinement.Refined(
                SourceRange(snapshot, startInclusive, endExclusive),
            )
        }
    }
}

enum class NonEmptySourceRangeFailure {
    EMPTY,
}

/** Proof that one snapshot-bound source range contains at least one UTF-16 code unit. */
class NonEmptySourceRange private constructor(
    val range: SourceRange,
) {
    companion object {
        /**
         * Proof transition: `SourceRange -> Refinement<NonEmptySourceRange,
         * NonEmptySourceRangeFailure>`.
         */
        fun create(
            range: SourceRange,
        ): Refinement<NonEmptySourceRange, NonEmptySourceRangeFailure> =
            if (range.startInclusive == range.endExclusive) {
                Refinement.Rejected(NonEmptySourceRangeFailure.EMPTY)
            } else {
                Refinement.Refined(NonEmptySourceRange(range))
            }
    }
}
