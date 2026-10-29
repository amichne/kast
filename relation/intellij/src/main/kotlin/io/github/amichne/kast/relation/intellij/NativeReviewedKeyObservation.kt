package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedDistinctKeyFailure
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import io.github.amichne.kast.workspace.intellij.read.call
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtFunctionLiteral

/** Conditional association remains valid when a bounded key proof is unavailable. */
internal fun nativeReviewedAssociationReturn(
    literal: KtFunction,
    source: ValueSite,
    binding: ReviewedCallbackValueBinding,
    context: IntellijCallbackFlowContext,
): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> =
    context.observation.call(IntellijReadCall.VALUE_REVIEWED_KEY_ADMISSION) {
        if (literal !is KtFunctionLiteral) {
            context.observation.terminated(IntellijReadTermination.VALUE_REVIEWED_KEYS_INPUT_UNPROVEN)
            return@call ReviewedValueTransfer.associatedReturn(source, binding)
        }
        when (val keys = readReviewedDistinctKeys(literal, source, binding, context)) {
            is Refinement.Refined -> {
                context.observation.terminated(IntellijReadTermination.VALUE_REVIEWED_KEYS_ADMITTED)
                ReviewedValueTransfer.associatedReturn(source, binding, keys.value)
            }
            is Refinement.Rejected ->
                when (val failure = keys.failure) {
                    is NativeReviewedKeyFailure.Unproved -> {
                        context.observation.terminated(failure.cause.keyTermination())
                        ReviewedValueTransfer.associatedReturn(source, binding)
                    }
                    is NativeReviewedKeyFailure.Limited -> {
                        context.observation.terminated(failure.cause.keyLimitTermination())
                        Refinement.Rejected(failure.cause.valueFlowCause)
                    }
                }
        }
    }

private fun ReviewedDistinctKeyFailure.keyTermination(): IntellijReadTermination =
    when (this) {
        ReviewedDistinctKeyFailure.DUPLICATE_KEYS -> IntellijReadTermination.VALUE_REVIEWED_KEYS_DUPLICATE
        ReviewedDistinctKeyFailure.ARTIFACT_MISMATCH -> IntellijReadTermination.VALUE_REVIEWED_ARTIFACT_MISMATCH
        ReviewedDistinctKeyFailure.CALLABLE_MISMATCH -> IntellijReadTermination.VALUE_REVIEWED_CALLABLE_MISMATCH
        ReviewedDistinctKeyFailure.INVOCATION_MISMATCH,
        ReviewedDistinctKeyFailure.FORMAL_MISMATCH,
        ReviewedDistinctKeyFailure.BASIS_MISMATCH,
        ReviewedDistinctKeyFailure.KEY_REFERENCE_MISMATCH,
        ReviewedDistinctKeyFailure.TUPLE_MISMATCH -> IntellijReadTermination.VALUE_REVIEWED_KEYS_OWNER_MISMATCH
        ReviewedDistinctKeyFailure.NOT_VARARG,
        ReviewedDistinctKeyFailure.UNSUPPORTED_ARGUMENT,
        ReviewedDistinctKeyFailure.UNKNOWN_STRING_CONSTANT,
        ReviewedDistinctKeyFailure.ARGUMENT_INVENTORY_MISMATCH,
        ReviewedDistinctKeyFailure.ARGUMENT_RANGE_MISMATCH,
        ReviewedDistinctKeyFailure.CALLBACK_SHAPE_UNSUPPORTED ->
            IntellijReadTermination.VALUE_REVIEWED_KEYS_INPUT_UNPROVEN
    }

private fun NativeReviewedKeyLimit.keyLimitTermination(): IntellijReadTermination =
    when (this) {
        NativeReviewedKeyLimit.WORK -> IntellijReadTermination.WORK_LIMIT
        NativeReviewedKeyLimit.TIME -> IntellijReadTermination.TIME_LIMIT
        NativeReviewedKeyLimit.BYTES -> IntellijReadTermination.BYTE_LIMIT
    }
