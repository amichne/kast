package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

enum class ValueInvocationSelectionKind {
    EXACT,
    QUALIFIED_SELECTOR,
}

/** A native-confirmed selector can name the same invocation as its full qualified expression. */
class ValueInvocationSelection
private constructor(
    val invocation: ValueInvocation,
    val selectedRange: ExactDeclarationTextRange,
    val kind: ValueInvocationSelectionKind,
) {
    companion object {
        fun exact(invocation: ValueInvocation): ValueInvocationSelection =
            ValueInvocationSelection(invocation, invocation.range, ValueInvocationSelectionKind.EXACT)

        /** K2 must establish the same selected call and expected callable before this detached shape admission. */
        fun fromCompiler(
            invocation: ValueInvocation,
            selectedRange: ExactDeclarationTextRange,
        ): Refinement<ValueInvocationSelection, ValueProducerSeedFailure> =
            when {
                selectedRange == invocation.range -> Refinement.Refined(exact(invocation))
                selectedRange.startInclusive > invocation.range.startInclusive &&
                    selectedRange.endExclusive == invocation.range.endExclusive ->
                    Refinement.Refined(
                        ValueInvocationSelection(
                            invocation,
                            selectedRange,
                            ValueInvocationSelectionKind.QUALIFIED_SELECTOR,
                        )
                    )
                else -> Refinement.Rejected(ValueProducerSeedFailure.ANCHOR_MISMATCH)
            }
    }
}
