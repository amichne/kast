package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ReviewedCallValueBinding
import io.github.amichne.kast.relation.contract.ReviewedEntryComponentInvocation
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry

/** The existing complete inventory supplies each invoked predecessor, including discarded named getters. */
internal fun nativeReviewedComponentOrder(
    components: List<Pair<KtDestructuringDeclarationEntry, NativeComponentTarget>>,
    declarationRange: ExactDeclarationTextRange,
): Refinement<List<Pair<KtDestructuringDeclarationEntry, NativeComponentTarget>>, ValueFlowUnsupportedCause> {
    val ordered = mutableListOf<Pair<KtDestructuringDeclarationEntry, NativeComponentTarget>>()
    for ((entry, target) in components) {
        if (target !is NativeComponentTarget.Reviewed) {
            ordered += entry to target
            continue
        }
        val preceding =
            when (val read = reviewedPredecessors(ordered)) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return read
            }
        val binding =
            when (
                val admitted =
                    ReviewedCallValueBinding.admitDestructuredEntry(
                        invocation = target.binding.invocation,
                        selected = target.binding.selected,
                        artifact = target.binding.artifact,
                        preceding = preceding,
                        declarationRange = declarationRange,
                    )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return Refinement.Rejected(admitted.failure.valueFlowCause())
            }
        ordered += entry to target.copy(binding = binding)
    }
    return Refinement.Refined(ordered.toList())
}

private fun reviewedPredecessors(
    components: List<Pair<KtDestructuringDeclarationEntry, NativeComponentTarget>>
): Refinement<List<ReviewedEntryComponentInvocation>, ValueFlowUnsupportedCause> {
    val preceding = mutableListOf<ReviewedEntryComponentInvocation>()
    for ((_, target) in components) {
        if (target !is NativeComponentTarget.Reviewed)
            return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        when (
            val admitted =
                ReviewedEntryComponentInvocation.fromCompiler(
                    invocation = target.binding.invocation,
                    selected = target.binding.selected,
                    artifact = target.binding.artifact,
                )
        ) {
            is Refinement.Refined -> preceding += admitted.value
            is Refinement.Rejected -> return Refinement.Rejected(admitted.failure.valueFlowCause())
        }
    }
    return Refinement.Refined(preceding.toList())
}
