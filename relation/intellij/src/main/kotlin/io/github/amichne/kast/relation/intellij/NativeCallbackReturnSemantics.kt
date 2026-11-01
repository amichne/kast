@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaNonPublicApi::class,
)

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

/** Reject explicit abrupt exits; an admitted route still needs its normal-return condition. */
internal fun nativeCallbackNormalReturn(
    function: KtFunctionLiteral,
    context: IntellijCallbackFlowContext,
): Refinement<Unit, ValueFlowUnsupportedCause> {
    when (val allowed = normalReturnPermit(context)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return allowed
    }
    return context.observation.observedAnalyze(function) {
        val statements =
            function.bodyExpression?.statements
                ?: return@observedAnalyze Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        if (statements.isEmpty()) return@observedAnalyze Refinement.Refined(Unit)
        normalCallbackStatements(statements, context)
    }
}

private fun KaSession.normalCallbackStatements(
    statements: List<KtExpression>,
    context: IntellijCallbackFlowContext,
): Refinement<Unit, ValueFlowUnsupportedCause> {
    when (val allowed = normalReturnPermit(context)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return allowed
    }
    val exits = computeExitPointSnapshot(statements)
    if (exits.hasJumps) return Refinement.Rejected(ValueFlowUnsupportedCause.ABRUPT_COMPLETION)
    val types = mutableListOf<KaType>()
    for (statement in statements) {
        when (val allowed = normalReturnPermit(context)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return allowed
        }
        val type =
            statement.expressionType ?: return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        types.add(type)
    }
    return normalCallbackTypes(types, context)
}

private fun KaSession.normalCallbackTypes(
    types: List<KaType>,
    context: IntellijCallbackFlowContext,
): Refinement<Unit, ValueFlowUnsupportedCause> {
    val proof =
        when (val read = nativeCompilerTypeProof(types) { normalReturnPermit(context) }) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return read
        }
    when (proof) {
        NativeCompilerTypeProof.ERROR_TYPE,
        NativeCompilerTypeProof.UNSUPPORTED_TYPE ->
            return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        NativeCompilerTypeProof.WORK_LIMIT_REACHED ->
            return Refinement.Rejected(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED)
        NativeCompilerTypeProof.ADMITTED -> Unit
    }
    for (type in types) {
        when (val allowed = normalReturnPermit(context)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return allowed
        }
        if (type.isNothingType && !type.isNullable)
            return Refinement.Rejected(ValueFlowUnsupportedCause.ABRUPT_COMPLETION)
    }
    return Refinement.Refined(Unit)
}

private fun normalReturnPermit(context: IntellijCallbackFlowContext): Refinement<Unit, ValueFlowUnsupportedCause> =
    when (context.currentWork()) {
        CallbackWorkAdmission.READY -> Refinement.Refined(Unit)
        CallbackWorkAdmission.WORK_LIMIT_REACHED -> Refinement.Rejected(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED)
        CallbackWorkAdmission.TIME_LIMIT_REACHED -> Refinement.Rejected(ValueFlowUnsupportedCause.TIME_LIMIT_REACHED)
    }

/** The compiler binds a labeled return to this callback before its returned expression can carry value. */
internal fun nativeExplicitCallbackReturn(
    expression: KtExpression,
    returned: KtReturnExpression,
    function: KtFunctionLiteral,
    observation: IntellijReadObservation,
    workAllowance: WorkAllowance,
): Refinement<NativeCallbackReturnSemantics, ValueFlowUnsupportedCause> {
    val target = observation.observedAnalyze(returned) { returned.resolveSymbol()?.psi }
    if (target?.originalElement != function.originalElement || returned.returnedExpression !== expression)
        return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
    return nativeCallbackReturnSemantics(expression, function, observation, workAllowance)
}
