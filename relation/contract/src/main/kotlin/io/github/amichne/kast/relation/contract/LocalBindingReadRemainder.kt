package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import java.util.Collections

/** Detached reference address. Multiple native reference kinds at one expression retain their exact reference range. */
data class LocalReferenceKey(
    val element: ExactDeclarationTextRange,
    val reference: ExactDeclarationTextRange,
    val kind: LocalReferenceKind,
)

/** A detached native reference implementation name distinguishes overlapping reference providers. */
@JvmInline
value class LocalReferenceKind private constructor(val value: String) {
    companion object {
        fun fromBoundary(value: String): Refinement<LocalReferenceKind, ValueFlowStepFailure> =
            if (value.isBlank() || value.length > MAXIMUM_REFERENCE_KIND_LENGTH)
                Refinement.Rejected(ValueFlowStepFailure.INVALID_PROGRESS)
            else Refinement.Refined(LocalReferenceKind(value))
    }
}

/** The complement of consumed addresses in the same authority's local reference search, independent of search order. */
class LocalBindingReadRemainder
private constructor(
    val source: ValueSite,
    val boundary: RelationSearchBoundary,
    val consumed: Set<LocalReferenceKey>,
    val emitted: Set<ValueSiteIdentity>,
) {
    val retainedBytes: Long
        get() = storageBytes(source, consumed.size, emitted.size)

    companion object {
        fun storageBytes(source: ValueSite, consumedCount: Int, emittedCount: Int): Long =
            2048L
                .addBytes(source.retainedBytes)
                .addBytes(consumedCount.toLong().multiplyBytes(REFERENCE_KEY_STORAGE_BYTES))
                .addBytes(emittedCount.toLong().multiplyBytes(source.retainedBytes))

        fun admit(
            source: ValueSite,
            boundary: RelationSearchBoundary,
            consumed: Set<LocalReferenceKey>,
            emitted: Set<ValueSiteIdentity>,
        ): Refinement<LocalBindingReadRemainder, ValueFlowStepFailure> {
            if (source.role != ValueRole.LocalBinding) return Refinement.Rejected(ValueFlowStepFailure.SOURCE_MISMATCH)
            if (
                consumed.any {
                    !source.enclosing.range.contains(it.element) || !it.element.contains(it.reference)
                }
            )
                return Refinement.Rejected(ValueFlowStepFailure.SOURCE_MISMATCH)
            if (
                emitted.any {
                    it.basis != source.basis || it.owner != source.identity.owner || it.role != ValueRole.LocalRead
                }
            )
                return Refinement.Rejected(ValueFlowStepFailure.BASIS_MISMATCH)
            return Refinement.Refined(
                LocalBindingReadRemainder(
                    source,
                    boundary,
                    Collections.unmodifiableSet(LinkedHashSet(consumed)),
                    Collections.unmodifiableSet(LinkedHashSet(emitted)),
                )
            )
        }
    }
}

enum class ValueFlowSuspensionCause {
    WORK_LIMIT_REACHED,
    RESULT_LIMIT_REACHED,
    BYTE_LIMIT_REACHED,
    TIME_LIMIT_REACHED,
}

/** One actual native grant and its receipt. Accumulation never invents a larger grant. */
data class ValueFlowWorkReceipt
internal constructor(
    val domain: RelationRequest,
    val examinedWorkUnits: RelationWorkCount,
    val returnedResults: RelationResultCount,
    val returnedBytes: RelationByteCount,
) {
    companion object {
        /** Rejected reads return no semantic payload; the actual grant and reported work remain evidence. */
        fun rejected(request: ValueFlowRequest, work: RelationWorkCount): ValueFlowWorkReceipt {
            val domain =
                when (val owner = request.source.enclosing) {
                    is RelationEndpoint.Subject ->
                        RelationRequest.start(owner, RelationMeaning.References, request.budget, request.boundary)
                    is RelationEndpoint.Resolved ->
                        RelationRequest.start(owner, RelationMeaning.References, request.budget, request.boundary)
                }
            return ValueFlowWorkReceipt(
                domain,
                work,
                (RelationResultCount.parse(0) as Refinement.Refined).value,
                (RelationByteCount.parse(0) as Refinement.Refined).value,
            )
        }
    }
}

private fun ExactDeclarationTextRange.contains(other: ExactDeclarationTextRange): Boolean =
    startInclusive <= other.startInclusive && endExclusive >= other.endExclusive

private const val MAXIMUM_REFERENCE_KIND_LENGTH = 256
private const val REFERENCE_KEY_STORAGE_BYTES = 1024L
