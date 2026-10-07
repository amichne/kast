package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.Collections

sealed interface CompleteCallbackDirectInvocationsFailure {
    data object EMPTY : CompleteCallbackDirectInvocationsFailure

    data object DUPLICATE : CompleteCallbackDirectInvocationsFailure

    data class InvalidUse(val cause: ImmutableCallbackInvocationFlowFailure) : CompleteCallbackDirectInvocationsFailure
}

/** All compiler-selected receiver alternatives at one actual invocation, without a source-wide exhaustion claim. */
class CompleteCallbackDirectInvocations
private constructor(
    val values: List<ImmutableCallbackInvocationUse.Direct>,
    val binding: CallbackDirectInvocationBinding,
) {
    val retainedBytes = values.fold(4096L) { bytes, value -> bytes.addBytes(value.value.retainedBytes) }

    companion object {
        fun fromCompiler(
            values: List<ImmutableCallbackValue>,
            binding: CallbackDirectInvocationBinding,
        ): Refinement<CompleteCallbackDirectInvocations, CompleteCallbackDirectInvocationsFailure> {
            if (values.isEmpty()) return Refinement.Rejected(CompleteCallbackDirectInvocationsFailure.EMPTY)
            if (values.distinct().size != values.size)
                return Refinement.Rejected(CompleteCallbackDirectInvocationsFailure.DUPLICATE)
            val uses = values.map { ImmutableCallbackInvocationUse.Direct(it, binding) }
            for (use in uses) when (val admitted = use.admitDirect(use.value.source)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected ->
                    return Refinement.Rejected(CompleteCallbackDirectInvocationsFailure.InvalidUse(admitted.failure))
            }
            return Refinement.Refined(CompleteCallbackDirectInvocations(Collections.unmodifiableList(uses), binding))
        }
    }
}

internal fun CompleteCallbackDirectInvocations.canonicalProjection(): String = buildString {
    fun field(value: String) {
        append(value.length).append(':').append(value)
    }
    field("CALLBACK_DIRECT_INVOCATIONS")
    field(binding.occurrence.file.toString())
    field(binding.occurrence.range.toString())
    field((binding.owner as RelationCallableBody.Named).compilerIdentity.toString())
    values.forEach { field(it.value.canonicalProjection()) }
}
