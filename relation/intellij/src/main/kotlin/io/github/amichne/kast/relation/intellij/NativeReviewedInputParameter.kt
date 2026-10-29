@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalParameter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.psi.KtFunctionLiteral

/** One actual compiler formal can supply separately qualified reviewed input branches. */
internal fun nativeReviewedInputParameter(
    binding: ReviewedCallbackValueBinding,
    literal: KtFunctionLiteral,
    context: IntellijCallbackFlowContext,
): Refinement<ValueFormalParameter, ValueFlowUnsupportedCause> {
    when (context.claimWork()) {
        CallbackWorkAdmission.READY -> Unit
        CallbackWorkAdmission.WORK_LIMIT_REACHED ->
            return Refinement.Rejected(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED)
        CallbackWorkAdmission.TIME_LIMIT_REACHED ->
            return Refinement.Rejected(ValueFlowUnsupportedCause.TIME_LIMIT_REACHED)
    }
    val position =
        when (val admitted = ValueArgumentPosition.parse(0)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return Refinement.Rejected(ValueFlowUnsupportedCause.EXTERNAL_CALL)
        }
    return when (
        val parameter =
            context.observation.observedAnalyze(literal) {
                nativeCallbackInputParameter(literal, ValueExecutionOwner.reviewedCallback(binding), position)
            }
    ) {
        is Refinement.Refined -> parameter
        is Refinement.Rejected -> {
            context.observation.terminated(IntellijReadTermination.VALUE_REVIEWED_FORMAL_MISMATCH)
            Refinement.Rejected(
                when (val failure = parameter.failure) {
                    is NativeCallbackInputFailure.Value -> failure.cause
                    is NativeCallbackInputFailure.Callback,
                    is NativeCallbackInputFailure.Contract,
                    is NativeCallbackInputFailure.Graph -> ValueFlowUnsupportedCause.NESTED_EXECUTION
                }
            )
        }
    }
}
