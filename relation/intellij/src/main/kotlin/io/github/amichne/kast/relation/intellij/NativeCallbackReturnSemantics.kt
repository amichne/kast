@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.WorkAllowance
import io.github.amichne.kast.relation.contract.ValueExecutionContext
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtReturnExpression

internal enum class NativeCallbackReturnSemantics {
    VALUE,
    UNIT_COERCION,
}

internal fun ValueFlowRequest.callbackReturnSemantics(
    expression: KtExpression,
    function: KtFunctionLiteral,
    observation: IntellijReadObservation,
): Refinement<NativeCallbackReturnSemantics, ValueFlowUnsupportedCause> =
    if (source.executionOwner.context == ValueExecutionContext.Declaration)
        Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
    else
        nativeCallbackReturnSemantics(
            expression = expression,
            function = function,
            observation = observation,
            workAllowance = budget.resources.workAllowance,
        )

/** The expression's own type alone does not prove that a lambda returns its value. */
internal fun nativeCallbackReturnSemantics(
    expression: KtExpression,
    function: KtFunctionLiteral,
    observation: IntellijReadObservation,
    workAllowance: WorkAllowance,
): Refinement<NativeCallbackReturnSemantics, ValueFlowUnsupportedCause> =
    if (expression is KtReturnExpression) Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
    else
        observation.observedAnalyze(function) {
            val expressionType = expression.expressionType
            val returnType = function.symbol.returnType
            if (expressionType == null) Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            else
                when (nativeCompilerTypeProof(listOf(expressionType, returnType), workAllowance)) {
                    NativeCompilerTypeProof.ERROR_TYPE,
                    NativeCompilerTypeProof.UNSUPPORTED_TYPE ->
                        Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
                    NativeCompilerTypeProof.WORK_LIMIT_REACHED ->
                        Refinement.Rejected(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED)
                    NativeCompilerTypeProof.ADMITTED -> callbackReturnTypes(expressionType, returnType)
                }
        }

private fun KaSession.callbackReturnTypes(
    expressionType: KaType,
    returnType: KaType,
): Refinement<NativeCallbackReturnSemantics, ValueFlowUnsupportedCause> =
    if (isNonNullableUnit(returnType) && !isNonNullableUnit(expressionType))
        Refinement.Refined(NativeCallbackReturnSemantics.UNIT_COERCION)
    else if (expressionType.isSubtypeOf(returnType)) Refinement.Refined(NativeCallbackReturnSemantics.VALUE)
    else Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)

private fun KaSession.isNonNullableUnit(type: KaType): Boolean = type.isUnitType && !type.isNullable

internal fun Refinement<NativeCallbackReturnSemantics, ValueFlowUnsupportedCause>.onCallbackReturn(
    value: () -> Unit,
    failure: (ValueFlowUnsupportedCause) -> Unit,
) {
    when (this) {
        is Refinement.Refined ->
            when (this.value) {
                NativeCallbackReturnSemantics.VALUE -> value()
                NativeCallbackReturnSemantics.UNIT_COERCION -> Unit
            }
        is Refinement.Rejected -> failure(this.failure)
    }
}
