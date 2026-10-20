package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

private const val MAX_CATCH_BRANCHES = 1024

@JvmInline
value class ValueInterpolationEntryIndex private constructor(val value: Int) {
    companion object {
        fun parse(value: Int): Refinement<ValueInterpolationEntryIndex, ValueTransferFailure> =
            if (value >= 0) Refinement.Refined(ValueInterpolationEntryIndex(value))
            else Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH)
    }
}

enum class ValueInterpolationScalarKind {
    STRING,
    BOOLEAN,
    CHAR,
    BYTE,
    SHORT,
    INT,
    LONG,
    FLOAT,
    DOUBLE,
}

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
    data class StringInterpolation
    private constructor(
        val templateRange: ExactDeclarationTextRange,
        val entryRange: ExactDeclarationTextRange,
        val entryIndex: ValueInterpolationEntryIndex,
        val scalarKind: ValueInterpolationScalarKind,
    ) : ValueTransferEvidence {
        companion object {
            /** K2 supplies the exact immediate entry and its non-nullable built-in scalar type. */
            fun fromCompiler(
                templateRange: ExactDeclarationTextRange,
                entryRange: ExactDeclarationTextRange,
                entryIndex: ValueInterpolationEntryIndex,
                scalarKind: ValueInterpolationScalarKind,
            ): Refinement<StringInterpolation, ValueTransferFailure> =
                if (
                    templateRange.startInclusive < entryRange.startInclusive &&
                        entryRange.endExclusive < templateRange.endExclusive
                )
                    Refinement.Refined(StringInterpolation(templateRange, entryRange, entryIndex, scalarKind))
                else Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH)
        }
    }

    @ConsistentCopyVisibility
    data class Guarded
    private constructor(
        val delegate: ValueTransferEvidence,
        val added: ValuePredicates,
    ) : ValueTransferEvidence {
        companion object {
            fun fromCompiler(
                delegate: ValueTransferEvidence,
                added: ValuePredicates,
            ): Refinement<Guarded, ValuePredicateFailure> =
                if (delegate is Guarded) Refinement.Rejected(ValuePredicateFailure.NESTED_GUARD)
                else Refinement.Refined(Guarded(delegate, added))
        }
    }

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
        if (this is Guarded) admitsGuard(source, target, kind)
        else
            source.predicates == target.predicates &&
                when (this) {
                    Direct ->
                        kind !in SOURCE_TRANSFER_KINDS &&
                            kind != ValueTransferKind.DERIVATION &&
                            source.valuePath == target.valuePath &&
                            source.callStack == target.callStack
                    is StringInterpolation -> admitsInterpolation(source, target, kind)
                    is ValueSourceTransfer -> admitsSource(source, target, kind)
                    is NormalBranchResult -> admitsNormal(source, target, kind)
                    is Guarded -> error("The guard branch precedes ordinary evidence")
                }

    val retainedBytes: Long
        get() =
            when (this) {
                Direct -> 0L
                is ValueSourceTransfer -> sourceEvidenceStorageBytes(operation)
                is NormalBranchResult -> 4096L
                is StringInterpolation -> 4096L
                is Guarded -> delegate.retainedBytes.addBytes(valuePredicateStorageBytes(added))
            }
}

private fun ValueTransferEvidence.StringInterpolation.admitsInterpolation(
    source: ValueSite,
    target: ValueSite,
    kind: ValueTransferKind,
): Boolean {
    if (kind != ValueTransferKind.DERIVATION || !interpolationRolesAndPaths(source, target)) return false
    if (!source.enclosing.hasSameProofAs(target.enclosing)) return false
    if (!source.executionOwner.hasSameProofAs(target.executionOwner)) return false
    if (source.predicates !== target.predicates) return false
    val sameContext = source.callStack === target.callStack || source.callStack.hasSameDirectProofAs(target.callStack)
    return sameContext &&
        target.range == templateRange &&
        entryRange.startInclusive <= source.range.startInclusive &&
        source.range.endExclusive <= entryRange.endExclusive
}

private fun interpolationRolesAndPaths(source: ValueSite, target: ValueSite): Boolean =
    (source.role == ValueRole.LocalRead || source.role == ValueRole.ExpressionResult) &&
        target.role == ValueRole.ExpressionResult &&
        source.valuePath == ValueAccessPath.Whole &&
        target.valuePath == ValueAccessPath.Whole

private fun ValueTransferEvidence.Guarded.admitsGuard(
    source: ValueSite,
    target: ValueSite,
    kind: ValueTransferKind,
): Boolean {
    if (!added.matchesBasis(source.basis)) return false
    val combined = source.predicates.plus(added.entries)
    if (combined !is Refinement.Refined || combined.value != target.predicates) return false
    val ordinaryTarget = target.withState(predicates = source.predicates)
    return ordinaryTarget is Refinement.Refined && delegate.admits(source, ordinaryTarget.value, kind)
}

private fun ValueTransferEvidence.NormalBranchResult.admitsNormal(
    source: ValueSite,
    target: ValueSite,
    kind: ValueTransferKind,
): Boolean =
    kind == ValueTransferKind.BRANCH_ALTERNATIVE &&
        (source.role == ValueRole.ExpressionResult || source.role == ValueRole.LocalRead) &&
        source.identity.owner == target.identity.owner &&
        source.executionOwner == target.executionOwner &&
        source.valuePath == target.valuePath &&
        source.callStack == target.callStack &&
        target.range == tryRange &&
        source.range.startInclusive >= branchRange.startInclusive &&
        source.range.endExclusive <= branchRange.endExclusive

private val SOURCE_TRANSFER_KINDS =
    setOf(
        ValueTransferKind.SOURCE_CALL_ENTRY,
        ValueTransferKind.SOURCE_CALL_RETURN,
        ValueTransferKind.FIELD_READ,
        ValueTransferKind.CONSTRUCTOR_FIELD,
    )
