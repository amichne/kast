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
    }
}

private fun historicalFileScope(file: SymbolDiscoveryFileIdentity.Workspace): SymbolSearchScope.ExactFile =
    SymbolSearchScope.ExactFile(
        file.path,
        SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
        SymbolGeneratedSourcePolicy.INCLUDE,
    )
