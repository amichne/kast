package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

enum class RelationOccurrenceFailure {
    INVALID_RANGE
}

@ConsistentCopyVisibility
data class RelationOccurrence
private constructor(
    val file: SymbolDiscoveryFileIdentity,
    val range: ExactDeclarationTextRange,
) {
    companion object {
        fun fromSource(
            file: SymbolDiscoveryFileIdentity,
            range: ExactDeclarationTextRange,
        ): RelationOccurrence = RelationOccurrence(file, range)

        /**
         * Proof transition: `(SymbolDiscoveryFileIdentity, Int, Int) -> Refinement< RelationOccurrence,
         * RelationOccurrenceFailure>`.
         *
         * Establishes an exact detached file and non-empty absolute source range. [RelationOccurrenceFailure] is the
         * closed expected failure. Raw offsets may enter only from a request-local K2-confirmed PSI occurrence.
         */
        fun fromBoundary(
            file: SymbolDiscoveryFileIdentity,
            rawStartInclusive: Int,
            rawEndExclusive: Int,
        ): Refinement<RelationOccurrence, RelationOccurrenceFailure> =
            when (val range = ExactDeclarationTextRange.parse(rawStartInclusive, rawEndExclusive)) {
                is Refinement.Refined -> Refinement.Refined(RelationOccurrence(file, range.value))
                is Refinement.Rejected -> Refinement.Rejected(RelationOccurrenceFailure.INVALID_RANGE)
            }
    }
}
