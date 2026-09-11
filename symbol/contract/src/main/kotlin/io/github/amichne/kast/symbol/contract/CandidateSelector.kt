package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

enum class CandidateOffsetFailure {
    NEGATIVE
}

/** One non-negative candidate offset; not exact source authority. */
@JvmInline
value class CandidateOffset private constructor(val value: Int) : Comparable<CandidateOffset> {
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
    NEGATIVE_ORDINAL,
    ORDINAL_OUT_OF_RANGE,
    EXTERNAL_SOURCE,
    WRONG_LOCATION_KIND,
    REVERSED_RANGE,
}

/**
 * One source candidate bound to read authority and retained scope, without compiler or committed-document identity.
 * Exact consumers must revalidate it before issuing stronger authority.
 */
sealed interface CandidateSelector {
    val lease: SemanticReadAuthority
    val scope: SymbolSearchScope
    val constraints: SymbolDiscoveryConstraints

    data class Declaration internal constructor(val selection: SymbolDiscoverySelection) : CandidateSelector {
        override val lease: SemanticReadAuthority = selection.lease
        override val scope: SymbolSearchScope = selection.scope
        override val constraints: SymbolDiscoveryConstraints = selection.constraints
    }

    data class File
    internal constructor(
        override val lease: SemanticReadAuthority,
        val file: SymbolDiscoveryFileIdentity.Workspace,
        override val scope: SymbolSearchScope,
        override val constraints: SymbolDiscoveryConstraints,
    ) : CandidateSelector

    data class Range
    internal constructor(
        override val lease: SemanticReadAuthority,
        val file: SymbolDiscoveryFileIdentity.Workspace,
        val startInclusive: CandidateOffset,
        val endExclusive: CandidateOffset,
        override val scope: SymbolSearchScope,
        override val constraints: SymbolDiscoveryConstraints,
    ) : CandidateSelector

    companion object {
        /** Retains an existing batch-owned declaration proof without strengthening it. */
        fun declaration(selection: SymbolDiscoverySelection): Refinement<Declaration, CandidateSelectorFailure> =
            if (selection.candidate.location is SymbolDiscoveryCandidateLocation.Declaration) {
                Refinement.Refined(Declaration(selection))
            } else {
                Refinement.Rejected(CandidateSelectorFailure.WRONG_LOCATION_KIND)
            }

        /** Issues a raw file candidate with explicit exact-file policy and no discovery restrictions. */
        fun file(candidate: SymbolDiscoveryCandidate): Refinement<File, CandidateSelectorFailure> {
            val location =
                candidate.location as? SymbolDiscoveryCandidateLocation.File
                    ?: return Refinement.Rejected(CandidateSelectorFailure.WRONG_LOCATION_KIND)
            val file =
                location.file as? SymbolDiscoveryFileIdentity.Workspace
                    ?: return Refinement.Rejected(CandidateSelectorFailure.EXTERNAL_SOURCE)
            return Refinement.Refined(restoreFile(candidate.lease, file))
        }

        /** Retains the exact file candidate and read restrictions proven by its discovery batch. */
        fun file(
            batch: SymbolDiscoveryBatch,
            rawOrdinal: Int,
        ): Refinement<File, CandidateSelectorFailure> {
            val candidate =
                when (val selected = selectCandidate(batch, rawOrdinal)) {
                    is Refinement.Refined -> selected.value
                    is Refinement.Rejected -> return selected
                }
            val location =
                candidate.location as? SymbolDiscoveryCandidateLocation.File
                    ?: return Refinement.Rejected(CandidateSelectorFailure.WRONG_LOCATION_KIND)
            val file =
                location.file as? SymbolDiscoveryFileIdentity.Workspace
                    ?: return Refinement.Rejected(CandidateSelectorFailure.EXTERNAL_SOURCE)
            return Refinement.Refined(restoreFile(batch.lease, file, batch.scope, batch.constraints))
        }

        /** Issues a raw range candidate with explicit exact-file policy and no discovery restrictions. */
        fun range(candidate: SymbolDiscoveryCandidate): Refinement<Range, CandidateSelectorFailure> {
            val location =
                candidate.location as? SymbolDiscoveryCandidateLocation.Text
                    ?: return Refinement.Rejected(CandidateSelectorFailure.WRONG_LOCATION_KIND)
            val file =
                location.file as? SymbolDiscoveryFileIdentity.Workspace
                    ?: return Refinement.Rejected(CandidateSelectorFailure.EXTERNAL_SOURCE)
            return restoreRange(
                candidate.lease,
                file,
                location.range.startInclusive.value,
                location.range.endExclusive.value,
            )
        }

        /** Retains the exact text candidate and read restrictions proven by its discovery batch. */
        fun range(
            batch: SymbolDiscoveryBatch,
            rawOrdinal: Int,
        ): Refinement<Range, CandidateSelectorFailure> {
            val candidate =
                when (val selected = selectCandidate(batch, rawOrdinal)) {
                    is Refinement.Refined -> selected.value
                    is Refinement.Rejected -> return selected
                }
            val location =
                candidate.location as? SymbolDiscoveryCandidateLocation.Text
                    ?: return Refinement.Rejected(CandidateSelectorFailure.WRONG_LOCATION_KIND)
            val file =
                location.file as? SymbolDiscoveryFileIdentity.Workspace
                    ?: return Refinement.Rejected(CandidateSelectorFailure.EXTERNAL_SOURCE)
            return restoreRange(
                batch.lease,
                file,
                location.range.startInclusive.value,
                location.range.endExclusive.value,
                batch.scope,
                batch.constraints,
            )
        }

        /** Restores a decoded range only when its coordinate invariants still hold. */
        fun restoreRange(
            lease: SemanticReadAuthority,
            file: SymbolDiscoveryFileIdentity.Workspace,
            rawStartInclusive: Int,
            rawEndExclusive: Int,
            scope: SymbolSearchScope = historicalFileScope(file),
            constraints: SymbolDiscoveryConstraints = SymbolDiscoveryConstraints.None,
        ): Refinement<Range, CandidateSelectorFailure> {
            val start =
                when (val parsed = CandidateOffset.parse(rawStartInclusive)) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> return Refinement.Rejected(CandidateSelectorFailure.REVERSED_RANGE)
                }
            val end =
                when (val parsed = CandidateOffset.parse(rawEndExclusive)) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> return Refinement.Rejected(CandidateSelectorFailure.REVERSED_RANGE)
                }
            return if (end < start) {
                Refinement.Rejected(CandidateSelectorFailure.REVERSED_RANGE)
            } else {
                Refinement.Refined(Range(lease, file, start, end, scope, constraints))
            }
        }

        /** Restores a decoded file candidate without introducing file-system authority. */
        fun restoreFile(
            lease: SemanticReadAuthority,
            file: SymbolDiscoveryFileIdentity.Workspace,
            scope: SymbolSearchScope = historicalFileScope(file),
            constraints: SymbolDiscoveryConstraints = SymbolDiscoveryConstraints.None,
        ): File = File(lease, file, scope, constraints)

        private fun selectCandidate(
            batch: SymbolDiscoveryBatch,
            rawOrdinal: Int,
        ): Refinement<SymbolDiscoveryCandidate, CandidateSelectorFailure> =
            when {
                rawOrdinal < 0 -> Refinement.Rejected(CandidateSelectorFailure.NEGATIVE_ORDINAL)
                rawOrdinal >= batch.candidates.size ->
                    Refinement.Rejected(CandidateSelectorFailure.ORDINAL_OUT_OF_RANGE)
                else -> Refinement.Refined(batch.candidates[rawOrdinal])
            }
    }
}

private fun historicalFileScope(file: SymbolDiscoveryFileIdentity.Workspace): SymbolSearchScope.ExactFile =
    SymbolSearchScope.ExactFile(
        file.path,
        SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
        SymbolGeneratedSourcePolicy.INCLUDE,
    )
