package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadLease

enum class CandidateOffsetFailure {
    NEGATIVE,
}

/** One non-negative, generation-bound candidate offset; not exact source authority. */
@JvmInline
value class CandidateOffset private constructor(
    val value: Int,
) : Comparable<CandidateOffset> {
    companion object {
        fun parse(raw: Int): Refinement<CandidateOffset, CandidateOffsetFailure> =
            if (raw < 0) {
                Refinement.Rejected(CandidateOffsetFailure.NEGATIVE)
            } else {
                Refinement.Refined(CandidateOffset(raw))
            }
    }

    override fun compareTo(other: CandidateOffset): Int = value.compareTo(other.value)
}

enum class CandidateSelectorFailure {
    EXTERNAL_SOURCE,
    WRONG_LOCATION_KIND,
    REVERSED_RANGE,
}

/**
 * One generation-bound source candidate that has not acquired compiler or committed-document
 * identity. Exact consumers must revalidate it before issuing stronger authority.
 */
sealed interface CandidateSelector {
    val lease: SemanticReadLease

    data class Declaration internal constructor(
        val selection: SymbolDiscoverySelection,
    ) : CandidateSelector {
        override val lease: SemanticReadLease = selection.lease
    }

    data class File internal constructor(
        override val lease: SemanticReadLease,
        val file: SymbolDiscoveryFileIdentity.Workspace,
    ) : CandidateSelector

    data class Range internal constructor(
        override val lease: SemanticReadLease,
        val file: SymbolDiscoveryFileIdentity.Workspace,
        val startInclusive: CandidateOffset,
        val endExclusive: CandidateOffset,
    ) : CandidateSelector

    companion object {
        /** Retains an existing batch-owned declaration proof without strengthening it. */
        fun declaration(
            selection: SymbolDiscoverySelection,
        ): Refinement<Declaration, CandidateSelectorFailure> =
            if (selection.candidate.location is SymbolDiscoveryCandidateLocation.Declaration) {
                Refinement.Refined(Declaration(selection))
            } else {
                Refinement.Rejected(CandidateSelectorFailure.WRONG_LOCATION_KIND)
            }

        /** Issues a file candidate only from an already admitted discovery file fact. */
        fun file(
            candidate: SymbolDiscoveryCandidate,
        ): Refinement<File, CandidateSelectorFailure> {
            val location = candidate.location as? SymbolDiscoveryCandidateLocation.File
                ?: return Refinement.Rejected(CandidateSelectorFailure.WRONG_LOCATION_KIND)
            val file = location.file as? SymbolDiscoveryFileIdentity.Workspace
                ?: return Refinement.Rejected(CandidateSelectorFailure.EXTERNAL_SOURCE)
            return Refinement.Refined(File(candidate.lease, file))
        }

        /** Issues a range candidate from one in-workspace discovery range. */
        fun range(
            candidate: SymbolDiscoveryCandidate,
        ): Refinement<Range, CandidateSelectorFailure> {
            val location = candidate.location as? SymbolDiscoveryCandidateLocation.Text
                ?: return Refinement.Rejected(CandidateSelectorFailure.WRONG_LOCATION_KIND)
            val file = location.file as? SymbolDiscoveryFileIdentity.Workspace
                ?: return Refinement.Rejected(CandidateSelectorFailure.EXTERNAL_SOURCE)
            return restoreRange(
                candidate.lease,
                file,
                location.range.startInclusive.value,
                location.range.endExclusive.value,
            )
        }

        /** Restores a decoded range only when its coordinate invariants still hold. */
        fun restoreRange(
            lease: SemanticReadLease,
            file: SymbolDiscoveryFileIdentity.Workspace,
            rawStartInclusive: Int,
            rawEndExclusive: Int,
        ): Refinement<Range, CandidateSelectorFailure> {
            val start = when (val parsed = CandidateOffset.parse(rawStartInclusive)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(CandidateSelectorFailure.REVERSED_RANGE)
            }
            val end = when (val parsed = CandidateOffset.parse(rawEndExclusive)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(CandidateSelectorFailure.REVERSED_RANGE)
            }
            return if (end < start) {
                Refinement.Rejected(CandidateSelectorFailure.REVERSED_RANGE)
            } else {
                Refinement.Refined(Range(lease, file, start, end))
            }
        }

        /** Restores a decoded file candidate without introducing file-system authority. */
        fun restoreFile(
            lease: SemanticReadLease,
            file: SymbolDiscoveryFileIdentity.Workspace,
        ): File = File(lease, file)
    }
}
