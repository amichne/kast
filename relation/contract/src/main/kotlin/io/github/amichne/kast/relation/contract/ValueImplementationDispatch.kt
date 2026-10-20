package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

private const val IMPLEMENTATION_IDENTITY_BASE_BYTES = 1024L
private const val CANONICAL_CHARACTER_STORAGE_BYTES = 4L

sealed interface ValueImplementationCoverage {
    data class Exhausted(val evidence: RelationCompleteCoverage) : ValueImplementationCoverage

    data class Incomplete(val evidence: RelationIncompleteCoverage) : ValueImplementationCoverage
}

sealed interface ValueImplementationDispatchFailure {
    data class CompilerRejected(val reason: RelationCompilerRejection) : ValueImplementationDispatchFailure

    data object MeaningMismatch : ValueImplementationDispatchFailure
}

/** Target-set coverage belongs to the existing relation read and establishes no runtime target selection. */
class ValueImplementationDispatch
private constructor(
    val batch: RelationBatch,
    val coverage: ValueImplementationCoverage,
) {
    internal val identity = ValueImplementationIdentity.from(batch, coverage)
    val retainedIdentityBytes: Long =
        IMPLEMENTATION_IDENTITY_BASE_BYTES + identity.canonical.length.toLong() * CANONICAL_CHARACTER_STORAGE_BYTES

    companion object {
        fun fromCompiler(
            read: RelationCompilation
        ): Refinement<ValueImplementationDispatch, ValueImplementationDispatchFailure> {
            val batch: RelationBatch
            val coverage: ValueImplementationCoverage
            when (read) {
                is RelationCompilation.Complete -> {
                    batch = read.batch
                    coverage = ValueImplementationCoverage.Exhausted(read.coverage)
                }
                is RelationCompilation.Qualified -> {
                    batch = read.batch
                    coverage = ValueImplementationCoverage.Incomplete(read.coverage)
                }
                is RelationCompilation.Rejected ->
                    return Refinement.Rejected(ValueImplementationDispatchFailure.CompilerRejected(read.reason))
            }
            if (batch.request.meaning != RelationMeaning.Implementations)
                return Refinement.Rejected(ValueImplementationDispatchFailure.MeaningMismatch)
            return Refinement.Refined(ValueImplementationDispatch(batch, coverage))
        }
    }

    override fun equals(other: Any?): Boolean = other is ValueImplementationDispatch && identity == other.identity

    override fun hashCode(): Int = identity.hashCode()
}

sealed interface ValueCallTarget {
    data object Direct : ValueCallTarget

    class Implementation
    internal constructor(
        val dispatch: ValueImplementationDispatch,
        val relation: RelationFact,
        val ownership: ValueSourceOwnership,
    ) : ValueCallTarget {
        override fun equals(other: Any?): Boolean =
            other is Implementation &&
                dispatch == other.dispatch &&
                relation.canonicalProjection() == other.relation.canonicalProjection() &&
                ownership == other.ownership

        override fun hashCode(): Int =
            31 * (31 * dispatch.hashCode() + relation.canonicalProjection().hashCode()) + ownership.hashCode()
    }
}
