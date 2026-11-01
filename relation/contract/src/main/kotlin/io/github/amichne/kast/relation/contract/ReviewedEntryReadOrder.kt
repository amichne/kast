package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

/** A detached component selection contains no preceding binding or execution-order assertion. */
class ReviewedEntryComponentInvocation
private constructor(
    val invocation: ValueInvocation,
    val selected: ReviewedOrdinaryLibraryCallable,
    val artifact: ReviewedLibraryArtifact,
) {
    val retainedBytes: Long
        get() = reviewedCallStorageBytes(invocation)

    companion object {
        fun fromCompiler(
            invocation: ValueInvocation,
            selected: ReviewedOrdinaryLibraryCallable,
            artifact: ReviewedArtifactEvidence,
        ): Refinement<ReviewedEntryComponentInvocation, ReviewedCallbackValueFailure> {
            if (selected.operation !in entryComponentOperations || artifact !is ReviewedLibraryArtifact)
                return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
            return when (val value = ReviewedCallValueBinding.admit(invocation, selected, artifact)) {
                is Refinement.Rejected -> value
                is Refinement.Refined ->
                    Refinement.Refined(ReviewedEntryComponentInvocation(invocation, selected, artifact))
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ReviewedEntryComponentInvocation &&
            invocation == other.invocation &&
            selected == other.selected &&
            artifact == other.artifact

    override fun hashCode(): Int = listOf(invocation, selected, artifact).hashCode()
}

sealed interface ReviewedEntryReadPrefix {
    data object Empty : ReviewedEntryReadPrefix

    data class FirstComponent internal constructor(val invocation: ReviewedEntryComponentInvocation) :
        ReviewedEntryReadPrefix
}

/** Native admission supplies the complete invoked prefix, after it omits underscore declarations. */
sealed interface ReviewedEntryReadOrder {
    data object Direct : ReviewedEntryReadOrder

    class Destructured
    private constructor(
        val declarationRange: ExactDeclarationTextRange,
        val prefix: ReviewedEntryReadPrefix,
    ) : ReviewedEntryReadOrder {
        companion object {
            internal fun fromCompiler(
                current: ReviewedCallValueBinding,
                declarationRange: ExactDeclarationTextRange,
                preceding: List<ReviewedEntryComponentInvocation>,
            ): Refinement<Destructured, ReviewedCallbackValueFailure> {
                if (
                    current.selected.operation !in entryComponentOperations ||
                        current.artifact !is ReviewedLibraryArtifact
                )
                    return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
                if (preceding.size > 1) return Refinement.Rejected(ReviewedCallbackValueFailure.FORMAL_MISMATCH)
                if (!current.invocation.executionOwner.range.containsValueRange(declarationRange))
                    return Refinement.Rejected(ReviewedCallbackValueFailure.OWNER_MISMATCH)
                if (!declarationRange.containsValueRange(current.invocation.range))
                    return Refinement.Rejected(ReviewedCallbackValueFailure.OWNER_MISMATCH)
                if (preceding.any { !declarationRange.containsValueRange(it.invocation.range) })
                    return Refinement.Rejected(ReviewedCallbackValueFailure.OWNER_MISMATCH)
                if (preceding.isEmpty())
                    return Refinement.Refined(Destructured(declarationRange, ReviewedEntryReadPrefix.Empty))
                val first = preceding.single()
                when (val admitted = admitEntryPrefix(current, first)) {
                    is Refinement.Refined -> Unit
                    is Refinement.Rejected -> return admitted
                }
                return Refinement.Refined(Destructured(declarationRange, ReviewedEntryReadPrefix.FirstComponent(first)))
            }
        }

        override fun equals(other: Any?): Boolean =
            other is Destructured && declarationRange == other.declarationRange && prefix == other.prefix

        override fun hashCode(): Int = listOf(declarationRange, prefix).hashCode()
    }

    val retainedBytes: Long
        get() =
            when (this) {
                Direct -> 0L
                is Destructured ->
                    when (val before = prefix) {
                        ReviewedEntryReadPrefix.Empty -> ENTRY_DECLARATION_RANGE_BYTES
                        is ReviewedEntryReadPrefix.FirstComponent ->
                            before.invocation.retainedBytes.addBytes(ENTRY_DECLARATION_RANGE_BYTES)
                    }
            }
}

private fun admitEntryPrefix(
    current: ReviewedCallValueBinding,
    first: ReviewedEntryComponentInvocation,
): Refinement<Unit, ReviewedCallbackValueFailure> {
    if (
        current.selected.operation != ReviewedLibraryOperation.MAP_ENTRY_COMPONENT2 ||
            first.selected.operation != ReviewedLibraryOperation.MAP_ENTRY_COMPONENT1
    )
        return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
    if (first.invocation.basis != current.basis) return Refinement.Rejected(ReviewedCallbackValueFailure.BASIS_MISMATCH)
    if (
        first.artifact.binaryArtifact != current.artifact.binaryArtifact ||
            first.artifact.sourceArtifact != current.artifact.sourceArtifact
    )
        return Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_MISMATCH)
    if (
        first.invocation.executionOwner != current.invocation.executionOwner ||
            first.invocation.range.endExclusive > current.invocation.range.startInclusive
    )
        return Refinement.Rejected(ReviewedCallbackValueFailure.OWNER_MISMATCH)
    return Refinement.Refined(Unit)
}

private val entryComponentOperations =
    setOf(
        ReviewedLibraryOperation.MAP_ENTRY_COMPONENT1,
        ReviewedLibraryOperation.MAP_ENTRY_COMPONENT2,
    )

private const val ENTRY_DECLARATION_RANGE_BYTES = 16L
