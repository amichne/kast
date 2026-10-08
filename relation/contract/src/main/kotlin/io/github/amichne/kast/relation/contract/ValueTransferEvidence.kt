package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

private const val MAX_CATCH_BRANCHES = 1024

@JvmInline
value class ValueCatchBranchIndex private constructor(val value: Int) {
    companion object {
        fun parse(value: Int): Refinement<ValueCatchBranchIndex, ValueTransferFailure> =
            if (value in 0 until MAX_CATCH_BRANCHES) Refinement.Refined(ValueCatchBranchIndex(value))
            else Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH)
    }
}

sealed interface ValueTryBranchAlternative {
    data object TryBody : ValueTryBranchAlternative

    data class CatchBody(val index: ValueCatchBranchIndex) : ValueTryBranchAlternative
}

/** Branch evidence is conditional on normal completion, never runtime reachability proof. */
sealed interface ValueTransferEvidence {
    data object Direct : ValueTransferEvidence

    @ConsistentCopyVisibility
    data class NormalBranchResult
    private constructor(
        val tryRange: ExactDeclarationTextRange,
        val branchRange: ExactDeclarationTextRange,
        val alternative: ValueTryBranchAlternative,
    ) : ValueTransferEvidence {
        companion object {
            /** The native boundary confirms final position, compiler type and absence of finally. */
            fun fromCompiler(
                tryRange: ExactDeclarationTextRange,
                branchRange: ExactDeclarationTextRange,
                alternative: ValueTryBranchAlternative,
            ): Refinement<NormalBranchResult, ValueTransferFailure> =
                if (
                    tryRange.startInclusive < branchRange.startInclusive &&
                        branchRange.endExclusive <= tryRange.endExclusive
                )
                    Refinement.Refined(NormalBranchResult(tryRange, branchRange, alternative))
                else Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH)
        }
    }

    fun admits(source: ValueSite, target: ValueSite, kind: ValueTransferKind): Boolean =
        when (this) {
            Direct -> true
            is NormalBranchResult ->
                kind == ValueTransferKind.BRANCH_ALTERNATIVE &&
                    (source.role == ValueRole.ExpressionResult || source.role == ValueRole.LocalRead) &&
                    source.identity.owner == target.identity.owner &&
                    target.range == tryRange &&
                    source.range.startInclusive >= branchRange.startInclusive &&
                    source.range.endExclusive <= branchRange.endExclusive
        }

    val retainedBytes: Long
        get() =
            when (this) {
                Direct -> 0L
                is NormalBranchResult -> 4096L
            }
}
