@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.WorkAllowance
import io.github.amichne.kast.relation.contract.ValueCastKind
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.types.Variance

/** Cast success preserves operand identity; it need not be guaranteed to occur. */
internal sealed interface NativeValueCastOutcome {
    data object Preserved : NativeValueCastOutcome

    data object NullResult : NativeValueCastOutcome

    data class Conditional(
        val kind: ValueCastKind,
        val originalType: CanonicalCompilerType,
        val targetType: CanonicalCompilerType,
    ) : NativeValueCastOutcome

    data class Unsupported(val cause: ValueFlowUnsupportedCause) : NativeValueCastOutcome
}

internal fun nativeValueCastOutcome(
    cast: KtBinaryExpressionWithTypeRHS,
    observation: IntellijReadObservation,
    allowance: WorkAllowance,
): NativeValueCastOutcome = observation.observedAnalyze(cast) { nativeValueCastOutcome(cast, allowance) }

internal fun KaSession.nativeValueCastOutcome(
    cast: KtBinaryExpressionWithTypeRHS,
    allowance: WorkAllowance,
): NativeValueCastOutcome = nativeCastOutcome(cast, cast.left.expressionType, allowance)

private fun KaSession.nativeCastOutcome(
    cast: KtBinaryExpressionWithTypeRHS,
    source: KaType?,
    allowance: WorkAllowance,
): NativeValueCastOutcome {
    val target = cast.right?.type
    if (source == null || target == null) return unsupportedCast()
    when (nativeCompilerTypeProof(listOf(source, target), allowance)) {
        NativeCompilerTypeProof.ADMITTED -> Unit
        NativeCompilerTypeProof.WORK_LIMIT_REACHED ->
            return NativeValueCastOutcome.Unsupported(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED)
        NativeCompilerTypeProof.ERROR_TYPE,
        NativeCompilerTypeProof.UNSUPPORTED_TYPE -> return unsupportedCast()
    }
    return classifyCast(source, target, cast)
}

private fun KaSession.classifyCast(
    source: KaType,
    target: KaType,
    cast: KtBinaryExpressionWithTypeRHS,
): NativeValueCastOutcome {
    val concrete = source as? KaClassType
    val nothing = concrete?.classId?.asSingleFqName()?.asString() == "kotlin.Nothing"
    if (nothing && !source.isMarkedNullable)
        return NativeValueCastOutcome.Unsupported(ValueFlowUnsupportedCause.ABRUPT_COMPLETION)
    if (source.isSubtypeOf(target)) return NativeValueCastOutcome.Preserved
    if (!nothing && !provesDisjointCast(concrete, target as? KaClassType)) return conditionalCast(source, target, cast)
    return when (cast.operationReference.getReferencedNameElementType()) {
        KtTokens.AS_SAFE -> NativeValueCastOutcome.NullResult
        KtTokens.AS_KEYWORD -> NativeValueCastOutcome.Unsupported(ValueFlowUnsupportedCause.ABRUPT_COMPLETION)
        else -> unsupportedCast()
    }
}

private fun KaSession.conditionalCast(
    source: KaType,
    target: KaType,
    cast: KtBinaryExpressionWithTypeRHS,
): NativeValueCastOutcome {
    val kind =
        when (cast.operationReference.getReferencedNameElementType()) {
            KtTokens.AS_KEYWORD -> ValueCastKind.UNSAFE
            KtTokens.AS_SAFE -> ValueCastKind.SAFE
            else -> return unsupportedCast()
        }
    val original =
        when (
            val type =
                CanonicalCompilerType.fromCompiler(
                    source.render(KaTypeRendererForSource.WITH_QUALIFIED_NAMES, Variance.INVARIANT)
                )
        ) {
            is Refinement.Refined -> type.value
            is Refinement.Rejected -> return unsupportedCast()
        }
    val destination =
        when (
            val type =
                CanonicalCompilerType.fromCompiler(
                    target.render(KaTypeRendererForSource.WITH_QUALIFIED_NAMES, Variance.INVARIANT)
                )
        ) {
            is Refinement.Refined -> type.value
            is Refinement.Rejected -> return unsupportedCast()
        }
    return NativeValueCastOutcome.Conditional(kind, original, destination)
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
    return nativeCastChainCompletion(
        casts = casts.asReversed(),
        observation = observation,
        allowance = allowance,
        permit = permit,
    )
}

private fun nativeCastChainCompletion(
    casts: List<KtBinaryExpressionWithTypeRHS>,
    observation: IntellijReadObservation,
    allowance: WorkAllowance,
    permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
): Refinement<Unit, ValueFlowUnsupportedCause> {
    var knownNull = false
    for (cast in casts) {
        when (val allowed = permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return allowed
        }
        val nullInput = knownNull
        val outcome =
            observation.observedAnalyze(cast) {
                val input = if (nullInput) builtinTypes.nullableNothing else cast.left.expressionType
                nativeCastOutcome(cast, input, allowance)
            }
        when (outcome) {
            NativeValueCastOutcome.Preserved,
            is NativeValueCastOutcome.Conditional -> Unit
            NativeValueCastOutcome.NullResult -> knownNull = true
            is NativeValueCastOutcome.Unsupported -> return Refinement.Rejected(outcome.cause)
        }
    }
    return Refinement.Refined(Unit)
}
