package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtValueArgument

internal fun nativeArgumentSiteRole(
    element: PsiElement,
    prepared: IntellijValueFlowCompilerAdapter.Prepared.Ready,
    executionOwner: ValueExecutionOwner,
    callbackContext: IntellijCallbackFlowContext,
): Refinement<ValueRole, ValueFlowUnsupportedCause> {
    val expression = element as? KtExpression ?: return roleRejected()
    val binary = expression.parent as? org.jetbrains.kotlin.psi.KtBinaryExpression
    if (binary != null) return nativeReviewedArgumentRole(expression, binary, executionOwner, callbackContext)
    val argument = element.parent as? KtValueArgument ?: return roleRejected()
    return when (
        val role =
            nativeValueArgument(
                expression = expression,
                argument = argument,
                scope = prepared.scope,
                projection = prepared.projection,
                executionOwner = executionOwner,
            )
    ) {
        is Refinement.Refined -> role
        is Refinement.Rejected -> {
            val call =
                (argument.parent as? org.jetbrains.kotlin.psi.KtValueArgumentList)?.parent
                    as? org.jetbrains.kotlin.psi.KtCallExpression
            if (role.failure == ValueFlowUnsupportedCause.EXTERNAL_CALL && call != null)
                nativeReviewedArgumentRole(
                    expression,
                    call.valueInvocationExpression() as KtExpression,
                    executionOwner,
                    callbackContext,
                )
            else role
        }
    }
}

internal fun nativeQualifiedReceiverSiteRole(
    expression: KtExpression,
    qualified: KtQualifiedExpression,
    prepared: IntellijValueFlowCompilerAdapter.Prepared.Ready,
    executionOwner: ValueExecutionOwner,
    callbackContext: IntellijCallbackFlowContext,
): Refinement<ValueRole, ValueFlowUnsupportedCause> {
    if (qualified.selectorExpression?.text in setOf("first", "second", "key", "value")) {
        when (val reviewed = nativeReviewedTupleRole(expression, qualified, executionOwner, callbackContext)) {
            is Refinement.Refined -> return reviewed
            is Refinement.Rejected ->
                when (reviewed.failure) {
                    ValueFlowUnsupportedCause.WORK_LIMIT_REACHED,
                    ValueFlowUnsupportedCause.TIME_LIMIT_REACHED,
                    ValueFlowUnsupportedCause.BYTE_LIMIT_REACHED -> return reviewed
                    else -> Unit
                }
        }
    }
    return when (
        val receiver =
            nativeValueReceiver(
                expression = expression,
                qualified = qualified,
                scope = prepared.scope,
                projection = prepared.projection,
                executionOwner = executionOwner,
            )
    ) {
        is Refinement.Refined -> receiver
        is Refinement.Rejected ->
            if (receiver.failure == ValueFlowUnsupportedCause.EXTERNAL_CALL)
                reviewedQualifiedReceiverRole(expression, qualified, executionOwner, callbackContext)
            else receiver
    }
}

private fun reviewedQualifiedReceiverRole(
    expression: KtExpression,
    qualified: KtQualifiedExpression,
    executionOwner: ValueExecutionOwner,
    callbackContext: IntellijCallbackFlowContext,
): Refinement<ValueRole, ValueFlowUnsupportedCause> {
    return when (
        val reviewed =
            nativeReviewedReceiverRole(
                receiverRange = sourceValueRange(expression),
                executionOwner = executionOwner,
                qualified = qualified,
                context = callbackContext,
            )
    ) {
        is Refinement.Refined -> reviewed
        is Refinement.Rejected ->
            if (
                (qualified.selectorExpression as? org.jetbrains.kotlin.psi.KtCallExpression)?.calleeExpression?.text in
                    setOf("to", "component1", "component2")
            )
                nativeReviewedTupleRole(expression, qualified, executionOwner, callbackContext)
            else reviewed
    }
}

private fun roleRejected(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
