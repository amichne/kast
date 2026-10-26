@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.WorkAllowance
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression

/** A cast preserves its operand only when the compiler proves the normal path. */
internal sealed interface NativeValueCastOutcome {
    data object Preserved : NativeValueCastOutcome

    data object NullResult : NativeValueCastOutcome

    data class Unsupported(val cause: ValueFlowUnsupportedCause) : NativeValueCastOutcome
}

internal fun nativeValueCastOutcome(
    cast: KtBinaryExpressionWithTypeRHS,
    observation: IntellijReadObservation,
    allowance: WorkAllowance,
): NativeValueCastOutcome =
    observation.observedAnalyze(cast) {
        val source = cast.left.expressionType
        val target = cast.right?.type
        if (source == null || target == null) return@observedAnalyze unsupportedCast()
        when (nativeCompilerTypeProof(listOf(source, target), allowance)) {
            NativeCompilerTypeProof.ADMITTED -> Unit
            NativeCompilerTypeProof.WORK_LIMIT_REACHED ->
                return@observedAnalyze NativeValueCastOutcome.Unsupported(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED)
            NativeCompilerTypeProof.ERROR_TYPE,
            NativeCompilerTypeProof.UNSUPPORTED_TYPE -> return@observedAnalyze unsupportedCast()
        }
        classifyCast(source, target, cast)
    }

private fun KaSession.classifyCast(
    source: KaType,
    target: KaType,
    cast: KtBinaryExpressionWithTypeRHS,
): NativeValueCastOutcome {
    val concrete = source as? KaClassType
    if (concrete?.classId?.asSingleFqName()?.asString() == "kotlin.Nothing" && !concrete.isMarkedNullable)
        return NativeValueCastOutcome.Unsupported(ValueFlowUnsupportedCause.ABRUPT_COMPLETION)
    if (source.isSubtypeOf(target)) return NativeValueCastOutcome.Preserved
    if (!provesDisjointCast(concrete, target as? KaClassType)) return unsupportedCast()
    return when (cast.operationReference.getReferencedNameElementType()) {
        KtTokens.AS_SAFE -> NativeValueCastOutcome.NullResult
        KtTokens.AS_KEYWORD -> NativeValueCastOutcome.Unsupported(ValueFlowUnsupportedCause.ABRUPT_COMPLETION)
        else -> unsupportedCast()
    }
}

private fun KaSession.provesDisjointCast(source: KaClassType?, target: KaClassType?): Boolean {
    if (source == null || target == null) return false
    if (source.isMarkedNullable || source.symbol.modality != KaSymbolModality.FINAL) return false
    return source.qualifiers.none { it.typeArguments.isNotEmpty() } &&
        target.qualifiers.none { it.typeArguments.isNotEmpty() }
}

private fun unsupportedCast() = NativeValueCastOutcome.Unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)

/** A selected result keeps its own null value, but no enclosing cast can erase an abrupt operand. */
internal fun nativeValueResultCompletion(
    expression: KtExpression,
    observation: IntellijReadObservation,
    allowance: WorkAllowance,
    permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
): Refinement<Unit, ValueFlowUnsupportedCause> {
    val casts = mutableListOf<KtBinaryExpressionWithTypeRHS>()
    var operand = expression
    while (operand is KtParenthesizedExpression || operand is KtBinaryExpressionWithTypeRHS) {
        when (val allowed = permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return allowed
        }
        operand =
            when (val current = operand) {
                is KtParenthesizedExpression ->
                    current.expression ?: return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
                is KtBinaryExpressionWithTypeRHS -> {
                    casts += current
                    current.left
                }
                else -> error("The loop admits only parentheses and casts")
            }
    }
    for (cast in casts.asReversed()) {
        when (val allowed = permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return allowed
        }
        when (val outcome = nativeValueCastOutcome(cast, observation, allowance)) {
            NativeValueCastOutcome.Preserved,
            NativeValueCastOutcome.NullResult -> Unit
            is NativeValueCastOutcome.Unsupported -> return Refinement.Rejected(outcome.cause)
        }
    }
    return Refinement.Refined(Unit)
}
