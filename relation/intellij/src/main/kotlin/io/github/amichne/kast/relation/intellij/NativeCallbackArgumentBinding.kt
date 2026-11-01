@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression

/** Request-local actual binding, not proof that a particular callback body executes or returns this input. */
internal sealed interface NativeCallbackArgumentBinding {
    data object NotCallback : NativeCallbackArgumentBinding

    data class Mapped(
        val receiver: KtExpression,
        val position: ValueArgumentPosition,
        val invocationRange: ExactDeclarationTextRange,
        val argumentRange: ExactDeclarationTextRange,
    ) : NativeCallbackArgumentBinding
}

/** Builtin function invoke may have no source PSI; its compiler mapping still identifies each exact actual. */
internal fun nativeCallbackArgumentBinding(
    call: KtCallExpression,
    expression: KtExpression,
    observation: IntellijReadObservation,
): Refinement<NativeCallbackArgumentBinding, ValueFlowUnsupportedCause> =
    observation.observedAnalyze(call) {
        val resolved = call.resolveCall()
        val callable = resolved?.signature?.symbol as? KaNamedFunctionSymbol
        when {
            resolved == null -> Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
            callable == null || !confirmsBuiltinFunctionInvoke(callable) ->
                Refinement.Refined(NativeCallbackArgumentBinding.NotCallback)
            else -> {
                val parameter = resolved.valueArgumentMapping[expression]?.symbol
                val position = ValueArgumentPosition.parse(callable.valueParameters.indexOfFirst { it == parameter })
                when (position) {
                    is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
                    is Refinement.Refined -> callbackArgumentReceiver(call, expression, position.value)
                }
            }
        }
    }

private fun callbackArgumentReceiver(
    call: KtCallExpression,
    expression: KtExpression,
    position: ValueArgumentPosition,
): Refinement<NativeCallbackArgumentBinding, ValueFlowUnsupportedCause> {
    val parent = call.parent
    val qualified = parent as? KtQualifiedExpression
    if (
        qualified?.selectorExpression === call &&
            (parent is KtSafeQualifiedExpression || call.calleeExpression?.text != "invoke")
    )
        return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    val receiver = if (qualified?.selectorExpression === call) qualified.receiverExpression else call.calleeExpression
    return if (receiver == null) Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
    else
        Refinement.Refined(
            NativeCallbackArgumentBinding.Mapped(
                receiver = receiver,
                position = position,
                invocationRange = sourceValueRange(call.valueInvocationExpression()),
                argumentRange = sourceValueRange(expression),
            )
        )
}
