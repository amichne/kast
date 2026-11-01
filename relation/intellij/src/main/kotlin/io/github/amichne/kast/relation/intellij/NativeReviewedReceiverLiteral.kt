@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/** Compiler argument mapping selects a callback independently of lexical argument order. */
internal fun nativeReviewedReceiverLiteral(
    qualified: KtQualifiedExpression,
    position: ValueArgumentPosition,
    context: IntellijCallbackFlowContext,
): Refinement<KtFunctionLiteral, ValueFlowUnsupportedCause> {
    val call = qualified.selectorExpression as? KtCallExpression
    return when (call) {
        null -> Refinement.Rejected(ValueFlowUnsupportedCause.EXTERNAL_CALL)
        else -> nativeReviewedCallLiteral(call, position, context)
    }
}

internal fun nativeReviewedCallLiteral(
    call: KtCallExpression,
    position: ValueArgumentPosition,
    context: IntellijCallbackFlowContext,
): Refinement<KtFunctionLiteral, ValueFlowUnsupportedCause> {
    when (val permit = reviewedPermit(context)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return Refinement.Rejected(permit.failure.valueFlowCause())
    }
    return context.observation.observedAnalyze(call) {
        val resolved = call.resolveCall()
        val formal = resolved?.signature?.symbol?.valueParameters?.getOrNull(position.value)
        val expression =
            resolved?.valueArgumentMapping?.entries?.singleOrNull { it.value.symbol == formal }?.key
                as? KtLambdaExpression
        when (val literal = expression?.functionLiteral) {
            null -> Refinement.Rejected(ValueFlowUnsupportedCause.EXTERNAL_CALL)
            else -> Refinement.Refined(literal)
        }
    }
}
