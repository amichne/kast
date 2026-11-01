package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

/** A successful null construction has no content contribution from the selected source value. */
@ConsistentCopyVisibility
data class ValueStringConstruction
private constructor(
    val source: ValueSite,
    val templateRange: ExactDeclarationTextRange,
    val entryRange: ExactDeclarationTextRange,
    val inputRange: ExactDeclarationTextRange,
) {
    val retainedBytes: Long
        get() = source.retainedBytes.addBytes(CONSTRUCTION_BYTES)

    companion object {
        /** The adapter proves Nothing? for the exact embedded result, not an arbitrary nullable expression. */
        fun constantNullFromCompiler(
            source: ValueSite,
            templateRange: ExactDeclarationTextRange,
            entryRange: ExactDeclarationTextRange,
            inputRange: ExactDeclarationTextRange,
        ): Refinement<ValueStringConstruction, ValueTransferFailure> {
            if (!source.executionOwner.range.containsValueRange(templateRange))
                return Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH)
            if (!source.valuePath.segments.isEmpty() || !inputRange.containsValueRange(source.range))
                return Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH)
            return when (
                ValueStringContent.fromCompiler(
                    templateRange,
                    entryRange,
                    inputRange,
                    ValueStringContentInput.NULLABLE_STRING,
                )
            ) {
                is Refinement.Refined ->
                    Refinement.Refined(ValueStringConstruction(source, templateRange, entryRange, inputRange))
                is Refinement.Rejected -> Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH)
            }
        }
    }
}

private const val CONSTRUCTION_BYTES = 256L
