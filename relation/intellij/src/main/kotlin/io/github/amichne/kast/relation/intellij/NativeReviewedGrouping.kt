package io.github.amichne.kast.relation.intellij

import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedLibraryOperation
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueSite
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/** Each supplier keeps its actual formal and body; only the one-callback overload preserves input elements. */
internal fun nativeReviewedGroupingInputs(
    request: ValueFlowRequest,
    binding: ReviewedCallbackValueBinding,
    literal: KtFunctionLiteral,
    context: IntellijCallbackFlowContext,
    retained: NativeValueFlowTransfers,
) {
    retained.adoptReviewed(groupingInput(request.source, binding, literal, context))
    if (binding.selected.operation == ReviewedLibraryOperation.ITERABLE_GROUP_BY) {
        retained.adoptReviewed(ReviewedValueTransfer.groupedReceiverElement(request.source, binding))
        return
    }
    when (val sibling = groupingSibling(literal, binding, context)) {
        is Refinement.Rejected -> retained.unresolved(sibling.failure)
        is Refinement.Refined ->
            retained.adoptReviewed(
                groupingInput(
                    source = request.source,
                    binding = sibling.value.binding,
                    literal = sibling.value.literal,
                    context = context,
                )
            )
    }
}

private fun groupingInput(
    source: ValueSite,
    binding: ReviewedCallbackValueBinding,
    literal: KtFunctionLiteral,
    context: IntellijCallbackFlowContext,
): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> =
    when (val formal = nativeReviewedInputParameter(binding, literal, context)) {
        is Refinement.Rejected -> formal
        is Refinement.Refined -> ReviewedValueTransfer.callbackInput(source, binding, formal.value)
    }

internal fun nativeReviewedGroupingReturn(
    literal: KtFunction,
    source: ValueSite,
    binding: ReviewedCallbackValueBinding,
    context: IntellijCallbackFlowContext,
): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> {
    if (binding.selected.operation == ReviewedLibraryOperation.ITERABLE_GROUP_BY)
        return ReviewedValueTransfer.groupedKeyResult(source, binding)
    val sibling =
        when (val read = groupingSibling(literal, binding, context)) {
            is Refinement.Rejected -> return read
            is Refinement.Refined -> read.value.binding
        }
    return when (binding.position.value) {
        0 -> ReviewedValueTransfer.groupedKeyResult(source, binding, sibling)
        1 -> ReviewedValueTransfer.groupedCallbackResult(source, sibling, binding)
        else -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
    }
}

private data class NativeGroupingSupplier(val binding: ReviewedCallbackValueBinding, val literal: KtFunctionLiteral)

private fun groupingSibling(
    literal: KtFunction,
    binding: ReviewedCallbackValueBinding,
    context: IntellijCallbackFlowContext,
): Refinement<NativeGroupingSupplier, ValueFlowUnsupportedCause> {
    val expression = literal.parent as? KtLambdaExpression
    if (expression == null) return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
    val call = PsiTreeUtil.getParentOfType(expression, KtCallExpression::class.java, true)
    val qualified = call?.valueInvocationExpression() as? KtQualifiedExpression
    if (qualified == null || sourceValueRange(qualified) != binding.occurrence.range)
        return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
    val position =
        when (val read = ValueArgumentPosition.parse(if (binding.position.value == 0) 1 else 0)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
        }
    val sibling =
        when (val read = nativeReviewedReceiverLiteral(qualified, position, context)) {
            is Refinement.Rejected -> return read
            is Refinement.Refined -> read.value
        }
    return when (val read = readReviewedCallbackValueBinding(sibling, binding.enclosing, context)) {
        is Refinement.Rejected -> Refinement.Rejected(read.failure.valueFlowCause())
        is Refinement.Refined -> Refinement.Refined(NativeGroupingSupplier(read.value, sibling))
    }
}
