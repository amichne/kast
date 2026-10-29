package io.github.amichne.kast.relation.intellij

import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueFailure
import io.github.amichne.kast.relation.contract.ReviewedDistinctKeyFailure
import io.github.amichne.kast.relation.contract.ReviewedDistinctStringKeys
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueSite
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression

/** Optional key proof cannot consume a work refusal as conditional semantic success. */
internal sealed interface NativeReviewedKeyFailure {
    data class Unproved(val cause: ReviewedDistinctKeyFailure) : NativeReviewedKeyFailure

    data class Limited(val cause: NativeReviewedKeyLimit) : NativeReviewedKeyFailure
}

internal fun readReviewedDistinctKeys(
    literal: KtFunctionLiteral,
    source: ValueSite,
    binding: ReviewedCallbackValueBinding,
    context: IntellijCallbackFlowContext,
): Refinement<ReviewedDistinctStringKeys, NativeReviewedKeyFailure> {
    val key =
        when (val read = readReviewedDirectKey(literal, source, binding, context)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return read
        }
    val receiver =
        when (val read = immediateAssociationReceiver(literal, binding, context)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return read
        }
    val input =
        when (val read = readReviewedStringList(receiver.expression, receiver.site.executionOwner, context)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return read
        }
    return ReviewedDistinctStringKeys.admit(input, key, receiver.site).keyRead()
}

private data class NativeAssociationReceiver(val expression: KtCallExpression, val site: ValueSite)

private fun immediateAssociationReceiver(
    literal: KtFunctionLiteral,
    binding: ReviewedCallbackValueBinding,
    context: IntellijCallbackFlowContext,
): Refinement<NativeAssociationReceiver, NativeReviewedKeyFailure> {
    val lambda =
        literal.parent as? KtLambdaExpression
            ?: return keyUnproved(ReviewedDistinctKeyFailure.CALLBACK_SHAPE_UNSUPPORTED)
    val call =
        PsiTreeUtil.getParentOfType(lambda, KtCallExpression::class.java, true)
            ?: return keyUnproved(ReviewedDistinctKeyFailure.CALLABLE_MISMATCH)
    val qualified =
        call.parent as? KtDotQualifiedExpression ?: return keyUnproved(ReviewedDistinctKeyFailure.UNSUPPORTED_ARGUMENT)
    if (qualified.selectorExpression !== call || sourceValueRange(qualified) != binding.occurrence.range)
        return keyUnproved(ReviewedDistinctKeyFailure.INVOCATION_MISMATCH)
    val expression =
        qualified.receiverExpression as? KtCallExpression
            ?: return keyUnproved(ReviewedDistinctKeyFailure.UNSUPPORTED_ARGUMENT)
    val owner = ValueExecutionOwner.declaration(binding.enclosing)
    when (val permit = reviewedPermit(context)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return Refinement.Rejected(permit.failure.keyFailure())
    }
    val role =
        when (
            val admitted =
                nativeReviewedReceiverRole(
                    receiverRange = sourceValueRange(expression),
                    executionOwner = owner,
                    qualified = qualified,
                    context = context,
                )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return Refinement.Rejected(admitted.failure.keyFlowFailure())
        }
    return when (
        val admitted =
            ValueSite.fromCompiler(
                enclosing = binding.enclosing,
                range = sourceValueRange(expression),
                role = role,
                executionOwner = owner,
            )
    ) {
        is Refinement.Refined -> Refinement.Refined(NativeAssociationReceiver(expression, admitted.value))
        is Refinement.Rejected -> keyUnproved(ReviewedDistinctKeyFailure.INVOCATION_MISMATCH)
    }
}

internal fun <T> keyUnproved(cause: ReviewedDistinctKeyFailure): Refinement<T, NativeReviewedKeyFailure> =
    Refinement.Rejected(NativeReviewedKeyFailure.Unproved(cause))

internal fun <T> Refinement<T, ReviewedDistinctKeyFailure>.keyRead(): Refinement<T, NativeReviewedKeyFailure> =
    when (this) {
        is Refinement.Refined -> this
        is Refinement.Rejected -> keyUnproved(failure)
    }

internal fun ReviewedCallbackValueFailure.keyFailure(): NativeReviewedKeyFailure =
    when (this) {
        ReviewedCallbackValueFailure.WORK_LIMIT_REACHED -> NativeReviewedKeyFailure.Limited(NativeReviewedKeyLimit.WORK)
        ReviewedCallbackValueFailure.TIME_LIMIT_REACHED -> NativeReviewedKeyFailure.Limited(NativeReviewedKeyLimit.TIME)
        else -> NativeReviewedKeyFailure.Unproved(ReviewedDistinctKeyFailure.ARTIFACT_MISMATCH)
    }

internal fun ValueFlowUnsupportedCause.keyFlowFailure(): NativeReviewedKeyFailure =
    when (this) {
        ValueFlowUnsupportedCause.WORK_LIMIT_REACHED -> NativeReviewedKeyFailure.Limited(NativeReviewedKeyLimit.WORK)
        ValueFlowUnsupportedCause.TIME_LIMIT_REACHED -> NativeReviewedKeyFailure.Limited(NativeReviewedKeyLimit.TIME)
        ValueFlowUnsupportedCause.BYTE_LIMIT_REACHED -> NativeReviewedKeyFailure.Limited(NativeReviewedKeyLimit.BYTES)
        else -> NativeReviewedKeyFailure.Unproved(ReviewedDistinctKeyFailure.INVOCATION_MISMATCH)
    }

internal enum class NativeReviewedKeyLimit(val valueFlowCause: ValueFlowUnsupportedCause) {
    WORK(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED),
    TIME(ValueFlowUnsupportedCause.TIME_LIMIT_REACHED),
    BYTES(ValueFlowUnsupportedCause.BYTE_LIMIT_REACHED),
}
