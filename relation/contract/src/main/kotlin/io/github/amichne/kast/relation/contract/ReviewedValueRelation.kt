package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

/** Applied positions remain part of relationship identity, independent of selected library authority. */
sealed interface ReviewedValueRelation {
    data class CallbackReceiverInput(val receiver: ReviewedCallbackReceiver) : ReviewedValueRelation

    data class CallbackCorrespondence(val semantics: ReviewedValueSemantics) : ReviewedValueRelation

    data class EntryInput(val entry: ReviewedInputEntryOrigin, val slot: MapEntrySlot) : ReviewedValueRelation {
        val selection: ReviewedEntrySelection
            get() = entry.selection
    }

    data class PreservedKey(val entry: ReviewedPreservedKeyOrigin) : ReviewedValueRelation {
        val selection: ReviewedEntrySelection
            get() = entry.selection
    }

    data class EntryProjection(val entry: ReviewedMapEntryOrigin, val slot: MapEntrySlot) : ReviewedValueRelation {
        val selection: ReviewedEntrySelection
            get() = entry.selection
    }

    data class MappedValue(val entry: ReviewedMappedEntryOrigin) : ReviewedValueRelation {
        val selection: ReviewedEntrySelection
            get() = entry.selection
    }

    data class GroupPlacement(val entry: ReviewedGroupBucket) : ReviewedValueRelation

    data class FilteredElements(val selection: ReviewedCollectionSelection) : ReviewedValueRelation

    data object NonNullCallbackResult : ReviewedValueRelation

    data class ReorderedElements(val selection: ReviewedCollectionSelection) : ReviewedValueRelation

    data class TupleConstruction(val origin: ReviewedTupleOrigin, val input: ReviewedTupleInput, val slot: PairSlot) :
        ReviewedValueRelation

    data class TupleProjection(val origin: ReviewedTupleOrigin, val slot: PairSlot) : ReviewedValueRelation

    data class AssociationComponent(val entry: ReviewedMapEntryCorrelation, val slot: MapEntrySlot) :
        ReviewedValueRelation {
        val keyEvidence: ReviewedEntryKeyEvidence
            get() = entry.keyEvidence
    }
}

enum class ReviewedCollectionSelection {
    ELEMENT,
    CONTENT,
}

enum class ReviewedTupleInput {
    EXTENSION_RECEIVER,
    ARGUMENT_ZERO,
    ARGUMENT_ONE,
}

enum class PairSlot {
    FIRST,
    SECOND,
}

enum class MapEntrySlot {
    KEY,
    VALUE,
}

/** Construction provenance omits value paths and conditions, so components cannot contain themselves. */
class ReviewedTupleOrigin
private constructor(
    val binding: ReviewedCallValueBinding,
    val callStack: ValueCallStack,
) {
    val basis
        get() = binding.basis

    val retainedBytes: Long
        get() =
            binding.retainedBytes.addBytes(
                callStack.frames.fold(0L) { sum, frame -> sum.addBytes(valueFrameStorageBytes(frame)) }
            )

    companion object {
        internal fun fromReviewed(
            binding: ReviewedCallValueBinding,
            callStack: ValueCallStack,
        ): Refinement<ReviewedTupleOrigin, ValueFlowUnsupportedCause> =
            if (
                binding.selected.operation in
                    setOf(ReviewedLibraryOperation.TO, ReviewedLibraryOperation.PAIR_CONSTRUCTOR) &&
                    callStack.matchesBasis(binding.basis)
            )
                Refinement.Refined(ReviewedTupleOrigin(binding, callStack))
            else Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
    }

    override fun equals(other: Any?): Boolean =
        other is ReviewedTupleOrigin && binding == other.binding && callStack == other.callStack

    override fun hashCode(): Int = 31 * binding.hashCode() + callStack.hashCode()
}

/** A static returned-pair family is not a runtime iteration or a unique-key proof. */
class ReviewedMapEntryCorrelation
private constructor(
    val binding: ReviewedCallbackValueBinding,
    val tupleOrigin: ReviewedTupleOrigin,
    val returnRange: ExactDeclarationTextRange,
    val returnExecution: ValueExecutionOwner,
    val callStack: ValueCallStack,
    val keyEvidence: ReviewedEntryKeyEvidence,
) : ReviewedMapEntryOrigin {
    override val basis
        get() = binding.basis

    override val retainedBytes: Long
        get() =
            binding.retainedBytes
                .addBytes(tupleOrigin.retainedBytes)
                .addBytes(valueExecutionStorageBytes(returnExecution))
                .addBytes(
                    when (val evidence = keyEvidence) {
                        ReviewedEntryKeyEvidence.Conditional -> 0L
                        is ReviewedEntryKeyEvidence.Distinct -> evidence.proof.retainedBytes
                    }
                )
                .addBytes(callStack.frames.fold(0L) { sum, frame -> sum.addBytes(valueFrameStorageBytes(frame)) })

    companion object {
        internal fun fromReviewed(
            source: ValueSite,
            binding: ReviewedCallbackValueBinding,
            keyEvidence: ReviewedEntryKeyEvidence = ReviewedEntryKeyEvidence.Conditional,
        ): Refinement<ReviewedMapEntryCorrelation, ValueFlowUnsupportedCause> {
            val component =
                source.valuePath.segments.firstOrNull() as? ValuePathSegment.PairComponent
                    ?: return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
            if (
                binding.selected.operation != ReviewedLibraryOperation.ITERABLE_ASSOCIATE ||
                    source.role != ValueRole.Return
            )
                return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
            if (
                source.executionOwner != ValueExecutionOwner.reviewedCallback(binding) ||
                    source.basis != component.origin.basis
            )
                return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
            val entry =
                ReviewedMapEntryCorrelation(
                    binding,
                    component.origin,
                    source.range,
                    source.executionOwner,
                    source.callStack,
                    keyEvidence,
                )
            if (keyEvidence is ReviewedEntryKeyEvidence.Distinct && !keyEvidence.proof.matches(entry))
                return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
            return Refinement.Refined(entry)
        }
    }

    internal fun matches(source: ValueSite): Boolean =
        source.role == ValueRole.Return &&
            source.range == returnRange &&
            source.executionOwner == returnExecution &&
            source.callStack == callStack &&
            (source.valuePath.segments.firstOrNull() as? ValuePathSegment.PairComponent)?.origin == tupleOrigin

    override fun equals(other: Any?): Boolean =
        other is ReviewedMapEntryCorrelation &&
            binding == other.binding &&
            tupleOrigin == other.tupleOrigin &&
            returnRange == other.returnRange &&
            returnExecution == other.returnExecution &&
            callStack == other.callStack &&
            keyEvidence == other.keyEvidence

    override fun hashCode(): Int =
        listOf(binding, tupleOrigin, returnRange, returnExecution, callStack, keyEvidence).hashCode()
}
