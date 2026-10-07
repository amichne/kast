package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty

internal data class CallbackValueRoute(val source: ValueSite, val transfers: List<ValueTransfer>)

/** Exact local ownership and admitted alias transfers remain ahead of callback expansion. */
internal class CallbackValueRoutes(private val context: IntellijCallbackFlowContext) {
    fun eligible(expression: KtNameReferenceExpression, frame: CallbackScanFrame): Boolean =
        expression.getReferencedName() == frame.prepared.parameter.name ||
            frame.aliases.keys.any { it.name == expression.getReferencedName() }

    fun root(
        expression: KtNameReferenceExpression,
        frame: CallbackScanFrame,
    ): Refinement<CallbackValueRoute, CallbackInvocationFlowCause> {
        val source =
            context.site(expression, frame.prepared.target, ValueRole.ExpressionResult)
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.PARAMETER_ESCAPES)
        return Refinement.Refined(CallbackValueRoute(source, emptyList()))
    }

    fun alias(
        expression: KtNameReferenceExpression,
        property: KtProperty,
        frame: CallbackScanFrame,
    ): Refinement<CallbackValueRoute, CallbackInvocationFlowCause> {
        val source =
            context.site(expression, frame.prepared.target, ValueRole.LocalRead)
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
        if (valueNativeOwnership(expression, frame.prepared.function) != NativeValueOwnership.ADMITTED)
            return Refinement.Rejected(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
        val prior = frame.aliases.getValue(property)
        return when (
            val transfer = ValueTransfer.fromCompiler(prior.last().target, source, ValueTransferKind.LOCAL_READ)
        ) {
            is Refinement.Refined -> Refinement.Refined(CallbackValueRoute(source, prior + transfer.value))
            is Refinement.Rejected -> Refinement.Rejected(CallbackInvocationFlowCause.PARAMETER_ESCAPES)
        }
    }

    fun immutableProperty(expression: KtNameReferenceExpression, frame: CallbackScanFrame): KtProperty? {
        var current: PsiElement = expression
        while (current.parent is KtParenthesizedExpression) current = current.parent
        val property = current.parent as? KtProperty ?: return null
        if (property.initializer !== current || !property.isLocal || property.isVar) return null
        return property.takeIf { valueNativeOwnership(it, frame.prepared.function) == NativeValueOwnership.ADMITTED }
    }
}
