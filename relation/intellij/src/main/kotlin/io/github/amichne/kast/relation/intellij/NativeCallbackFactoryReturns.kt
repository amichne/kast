@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtReturnExpression

/** Inventories only compiler-owned returns, under the caller's work and time grant. */
internal fun callbackFactoryReturns(
    function: KtNamedFunction,
    permit: () -> Refinement<Unit, CallbackInvocationFlowCause>,
): Refinement<List<KtExpression>, CallbackInvocationFlowCause> {
    val body = function.bodyExpression ?: return rejected(CallbackInvocationFlowCause.EXTERNAL_CALLABLE)
    if (!function.hasBlockBody()) return Refinement.Refined(listOf(body))
    val pending = ArrayDeque<PsiElement>()
    pending.add(body)
    val results = mutableListOf<KtExpression>()
    while (pending.isNotEmpty()) {
        when (val permit = permit()) {
            is Refinement.Rejected -> return permit
            is Refinement.Refined -> Unit
        }
        val element = pending.removeFirst()
        if (element is KtReturnExpression) {
            when (val collected = collectReturn(element, function, results)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return collected
            }
        }
        element.children.forEach(pending::addLast)
    }
    return if (results.isEmpty()) rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
    else Refinement.Refined(results)
}

private fun collectReturn(
    element: KtReturnExpression,
    function: KtNamedFunction,
    results: MutableList<KtExpression>,
): Refinement<Unit, CallbackInvocationFlowCause> {
    val target =
        analyze(element) { element.resolveSymbol()?.psi }
            ?: return rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
    if (target.originalElement != function.originalElement) return Refinement.Refined(Unit)
    return when (val expression = factoryReturnedExpression(element, function)) {
        is Refinement.Refined -> {
            results += expression.value
            Refinement.Refined(Unit)
        }
        is Refinement.Rejected -> expression
    }
}

private fun rejected(cause: CallbackInvocationFlowCause) = Refinement.Rejected(cause)
