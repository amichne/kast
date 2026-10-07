package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLabeledExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList

/** Syntactic supply facts alone never establish callback activation or named-call ownership. */
internal sealed interface IntellijCallbackLambdaSupply {
    data class Argument(val argument: KtValueArgument, val expression: KtExpression) : IntellijCallbackLambdaSupply

    data class Invocation(val call: KtCallExpression) : IntellijCallbackLambdaSupply

    data class DefaultParameter(val parameter: KtParameter, val expression: KtExpression) : IntellijCallbackLambdaSupply

    data object Stored : IntellijCallbackLambdaSupply

    data class Returned(val occurrence: PsiElement) : IntellijCallbackLambdaSupply

    data object Unsupported : IntellijCallbackLambdaSupply
}

internal fun classifyCallbackLambdaSupply(lambda: KtLambdaExpression): IntellijCallbackLambdaSupply {
    var expression: KtExpression = lambda
    while (expression.parent is KtParenthesizedExpression || expression.parent is KtLabeledExpression) expression =
        expression.parent as KtExpression
    return classifyCallbackValueSupply(expression)
}

internal fun classifyCallbackFunctionSupply(function: KtFunction): IntellijCallbackLambdaSupply =
    when (function) {
        is KtFunctionLiteral ->
            (function.parent as? KtLambdaExpression)?.let(::classifyCallbackLambdaSupply)
                ?: IntellijCallbackLambdaSupply.Unsupported
        is KtNamedFunction -> {
            if (function.name != null) IntellijCallbackLambdaSupply.Unsupported
            else {
                var expression: KtExpression = function
                while (
                    expression.parent is KtParenthesizedExpression || expression.parent is KtLabeledExpression
                ) expression = expression.parent as KtExpression
                classifyCallbackValueSupply(expression)
            }
        }
        else -> IntellijCallbackLambdaSupply.Unsupported
    }

internal fun classifyCallbackValueSupply(expression: KtExpression): IntellijCallbackLambdaSupply {
    return when (val parent = expression.parent) {
        is KtValueArgument -> IntellijCallbackLambdaSupply.Argument(parent, expression)
        is KtCallExpression -> classifyDirectCallback(parent, expression)
        is KtQualifiedExpression -> classifyQualifiedCallback(parent, expression)
        is KtParameter -> classifyDefaultCallback(parent, expression)
        is KtProperty -> classifyStoredCallback(parent, expression)
        is KtReturnExpression -> classifyExplicitlyReturnedCallback(parent, expression)
        is KtNamedFunction -> classifyReturnedCallback(parent, expression)
        else -> IntellijCallbackLambdaSupply.Unsupported
    }
}

private fun classifyDirectCallback(call: KtCallExpression, expression: KtExpression): IntellijCallbackLambdaSupply =
    if (call.calleeExpression === expression) IntellijCallbackLambdaSupply.Invocation(call)
    else IntellijCallbackLambdaSupply.Unsupported

private fun classifyQualifiedCallback(
    qualified: KtQualifiedExpression,
    expression: KtExpression,
): IntellijCallbackLambdaSupply {
    val call = qualified.selectorExpression as? KtCallExpression
    return if (qualified.receiverExpression === expression && call?.calleeExpression?.text == "invoke")
        IntellijCallbackLambdaSupply.Invocation(call)
    else IntellijCallbackLambdaSupply.Unsupported
}

private fun classifyDefaultCallback(parameter: KtParameter, expression: KtExpression): IntellijCallbackLambdaSupply =
    if (parameter.defaultValue === expression) IntellijCallbackLambdaSupply.DefaultParameter(parameter, expression)
    else IntellijCallbackLambdaSupply.Unsupported

private fun classifyExplicitlyReturnedCallback(
    returned: KtReturnExpression,
    expression: KtExpression,
): IntellijCallbackLambdaSupply =
    if (returned.returnedExpression === expression) IntellijCallbackLambdaSupply.Returned(returned)
    else IntellijCallbackLambdaSupply.Unsupported

private fun classifyStoredCallback(property: KtProperty, expression: KtExpression): IntellijCallbackLambdaSupply =
    if (property.isLocal && property.initializer === expression) IntellijCallbackLambdaSupply.Stored
    else IntellijCallbackLambdaSupply.Unsupported

private fun classifyReturnedCallback(
    function: KtNamedFunction,
    expression: KtExpression,
): IntellijCallbackLambdaSupply =
    if (function.bodyExpression === expression && !function.hasBlockBody())
        IntellijCallbackLambdaSupply.Returned(expression)
    else IntellijCallbackLambdaSupply.Unsupported

internal fun supplyingCallbackCall(argument: KtValueArgument): KtCallElement? =
    when (val parent = argument.parent) {
        is KtCallElement -> parent
        is KtValueArgumentList -> parent.parent as? KtCallElement
        else -> null
    }

internal fun callbackSupplyArgument(
    lambda: KtLambdaExpression
): Refinement<IntellijCallbackLambdaSupply.Argument, CallbackInvocationFlowCause> =
    when (val supply = classifyCallbackLambdaSupply(lambda)) {
        is IntellijCallbackLambdaSupply.Argument -> Refinement.Refined(supply)
        IntellijCallbackLambdaSupply.Stored -> Refinement.Rejected(CallbackInvocationFlowCause.STORED_CALLBACK)
        is IntellijCallbackLambdaSupply.Returned -> Refinement.Rejected(CallbackInvocationFlowCause.RETURNED_CALLBACK)
        is IntellijCallbackLambdaSupply.DefaultParameter,
        is IntellijCallbackLambdaSupply.Invocation,
        IntellijCallbackLambdaSupply.Unsupported ->
            Refinement.Rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
    }
