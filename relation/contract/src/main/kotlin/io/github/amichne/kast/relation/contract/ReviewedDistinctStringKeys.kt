package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

/** The resolved reference is the callback formal in the key input of its sole returned tuple. */
class ReviewedDirectFormalKey
private constructor(
    val binding: ReviewedCallbackValueBinding,
    val formal: ValueFormalParameter,
    val tupleOrigin: ReviewedTupleOrigin,
    val keyRange: ExactDeclarationTextRange,
    val returnRange: ExactDeclarationTextRange,
) {
    val retainedBytes: Long
        get() =
            STRING_PROOF_BYTES.addBytes(binding.retainedBytes)
                .addBytes(tupleOrigin.retainedBytes)
                .addBytes(valueExecutionStorageBytes(formal.executionOwner))
                .addBytes(formal.callable.detachedTextUnits().multiplyBytes(2))
                .addBytes(formal.name.value.length.toLong().multiplyBytes(2))

    companion object {
        /** Native admission resolves the key reference and inventories every callback statement before this call. */
        fun fromCompiler(
            binding: ReviewedCallbackValueBinding,
            resolvedFormal: ValueFormalParameter,
            tupleOrigin: ReviewedTupleOrigin,
            keyUse: ValueSite,
            bodyStatementRanges: List<ExactDeclarationTextRange>,
        ): Refinement<ReviewedDirectFormalKey, ReviewedDistinctKeyFailure> {
            if (binding.basis != tupleOrigin.basis || binding.basis != keyUse.basis)
                return distinctKeyRejected(ReviewedDistinctKeyFailure.BASIS_MISMATCH)
            val soleStatement = bodyStatementRanges.singleOrNull()
            if (soleStatement != tupleOrigin.binding.invocation.range)
                return distinctKeyRejected(ReviewedDistinctKeyFailure.CALLBACK_SHAPE_UNSUPPORTED)
            if (!binding.admitsDirectKeyFormal(resolvedFormal))
                return distinctKeyRejected(ReviewedDistinctKeyFailure.FORMAL_MISMATCH)
            if (!keyUse.isDirectKeyInput(tupleOrigin, binding))
                return distinctKeyRejected(ReviewedDistinctKeyFailure.KEY_REFERENCE_MISMATCH)
            return Refinement.Refined(
                ReviewedDirectFormalKey(
                    binding,
                    resolvedFormal,
                    tupleOrigin,
                    keyUse.range,
                    tupleOrigin.binding.invocation.range,
                )
            )
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ReviewedDirectFormalKey &&
            binding == other.binding &&
            formal == other.formal &&
            tupleOrigin == other.tupleOrigin &&
            keyRange == other.keyRange &&
            returnRange == other.returnRange

    override fun hashCode(): Int = listOf(binding, formal, tupleOrigin, keyRange, returnRange).hashCode()
}

private fun ReviewedCallbackValueBinding.admitsDirectKeyFormal(formal: ValueFormalParameter): Boolean {
    if (selected.operation != ReviewedLibraryOperation.ITERABLE_ASSOCIATE || formal.position.value != 0) return false
    return formal.executionOwner == ValueExecutionOwner.reviewedCallback(this) &&
        formal.callable.valueIdentity == enclosing.valueIdentity
}

private fun ValueSite.isDirectKeyInput(origin: ReviewedTupleOrigin, binding: ReviewedCallbackValueBinding): Boolean {
    if (valuePath != ValueAccessPath.Whole || callStack != origin.callStack) return false
    val owner = ValueExecutionOwner.reviewedCallback(binding)
    if (executionOwner != owner || origin.binding.invocation.executionOwner != owner) return false
    if (!origin.binding.invocation.range.containsValueRange(range)) return false
    return when (val role = role) {
        is ValueRole.Receiver ->
            origin.binding.selected.operation == ReviewedLibraryOperation.TO &&
                role.kind == ValueReceiverKind.EXTENSION &&
                role.call == origin.binding.invocation
        is ValueRole.Argument ->
            origin.binding.selected.operation == ReviewedLibraryOperation.PAIR_CONSTRUCTOR &&
                role.position.value == 0 &&
                role.call == origin.binding.invocation
        else -> false
    }
}

/** Complete immutable input and direct key correspondence discharge one exact association's uniqueness premise. */
class ReviewedDistinctStringKeys
private constructor(
    val input: ReviewedStringListInput,
    val key: ReviewedDirectFormalKey,
    val associationInvocation: ValueInvocation,
    val receiverRange: ExactDeclarationTextRange,
) {
    val retainedBytes: Long
        get() =
            STRING_PROOF_BYTES.addBytes(input.retainedBytes)
                .addBytes(key.retainedBytes)
                .addBytes(associationInvocation.enclosing.detachedTextUnits().multiplyBytes(2))
                .addBytes(associationInvocation.callable.detachedTextUnits().multiplyBytes(2))
                .addBytes(valueExecutionStorageBytes(associationInvocation.executionOwner))

    companion object {
        fun admit(
            input: ReviewedStringListInput,
            key: ReviewedDirectFormalKey,
            receiver: ValueSite,
        ): Refinement<ReviewedDistinctStringKeys, ReviewedDistinctKeyFailure> {
            if (input.invocation.basis != key.binding.basis || receiver.basis != key.binding.basis)
                return distinctKeyRejected(ReviewedDistinctKeyFailure.BASIS_MISMATCH)
            val role =
                receiver.role as? ValueRole.Receiver
                    ?: return distinctKeyRejected(ReviewedDistinctKeyFailure.INVOCATION_MISMATCH)
            if (!receiver.isImmediateStringListReceiver(input, key.binding, role))
                return distinctKeyRejected(ReviewedDistinctKeyFailure.INVOCATION_MISMATCH)
            if (input.arguments.map { it.value }.toSet().size != input.argumentCount)
                return distinctKeyRejected(ReviewedDistinctKeyFailure.DUPLICATE_KEYS)
            return Refinement.Refined(ReviewedDistinctStringKeys(input, key, role.call, receiver.range))
        }
    }

    internal fun matches(entry: ReviewedMapEntryCorrelation): Boolean =
        key.binding == entry.binding &&
            key.tupleOrigin == entry.tupleOrigin &&
            key.returnRange == entry.returnRange &&
            key.formal.executionOwner == entry.returnExecution

    override fun equals(other: Any?): Boolean =
        other is ReviewedDistinctStringKeys &&
            input == other.input &&
            key == other.key &&
            associationInvocation == other.associationInvocation &&
            receiverRange == other.receiverRange

    override fun hashCode(): Int = listOf(input, key, associationInvocation, receiverRange).hashCode()
}

private fun ValueSite.isImmediateStringListReceiver(
    input: ReviewedStringListInput,
    binding: ReviewedCallbackValueBinding,
    role: ValueRole.Receiver,
): Boolean {
    if (valuePath != ValueAccessPath.Whole || role.kind != ValueReceiverKind.EXTENSION) return false
    if (range != input.invocation.range || executionOwner != input.invocation.executionOwner) return false
    if (
        executionOwner != ValueExecutionOwner.declaration(binding.enclosing) || role.call.enclosing != binding.enclosing
    )
        return false
    return role.call.range == binding.occurrence.range &&
        role.call.callable.valueIdentity == ValueDeclarationIdentity.fromCompiler(binding.target) &&
        range.endExclusive <= binding.body.range.startInclusive
}

sealed interface ReviewedEntryKeyEvidence {
    data object Conditional : ReviewedEntryKeyEvidence

    data class Distinct(val proof: ReviewedDistinctStringKeys) : ReviewedEntryKeyEvidence
}
