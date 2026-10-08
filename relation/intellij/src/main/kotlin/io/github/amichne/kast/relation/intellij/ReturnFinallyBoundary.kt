package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtTryExpression

/** A returned value crosses every enclosing try boundary up to its already admitted executable owner. */
internal fun returnCrossesFinally(expression: KtReturnExpression, owner: PsiElement): Boolean {
    var current: PsiElement? = expression.parent
    while (current != null && current !== owner) {
        if (current is KtTryExpression && current.finallyBlock != null) return true
        current = current.parent
    }
    return false
}

/** The native caller has already confirmed that this return targets the exact factory owner. */
internal fun factoryReturnedExpression(
    expression: KtReturnExpression,
    owner: PsiElement,
): Refinement<KtExpression, CallbackInvocationFlowCause> =
    if (returnCrossesFinally(expression, owner)) Refinement.Rejected(CallbackInvocationFlowCause.FINALLY_UNSUPPORTED)
    else
        when (val value = expression.returnedExpression) {
            null -> Refinement.Rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
            else -> Refinement.Refined(value)
        }
