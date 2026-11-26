package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtExpression

private val IDENTITY_OPERATORS = setOf(KtTokens.EQEQEQ, KtTokens.EXCLEQEQEQ)
private val STRUCTURAL_OPERATORS = setOf(KtTokens.EQEQ, KtTokens.EXCLEQ)

/** Library classes whose `equals` is final and reads its operands without retaining either one. */
private val REVIEWED_EQUALS_OWNERS =
    setOf(
        "kotlin.String",
        "kotlin.Boolean",
        "kotlin.Char",
        "kotlin.Byte",
        "kotlin.Short",
        "kotlin.Int",
        "kotlin.Long",
        "kotlin.Float",
        "kotlin.Double",
    )

private const val ANY_EQUALS = "kotlin.Any.equals"

/**
 * An equality test consumes its operands: its Boolean result carries neither operand's value.
 *
 * Identity equality invokes no code. Structural equality invokes the `equals` of the left operand's runtime class. The
 * operand is discharged only when the compiler proves which `equals` runs and that it cannot retain an operand: the
 * left operand's static class is final, so no subclass can override `equals`, and its `equals` is either a reviewed
 * library member or the identity comparison inherited from `kotlin.Any`. Every other equality keeps its resolved-call
 * obligation, including open classes, interfaces, type parameters, data classes, and source overrides.
 */
internal fun KtBinaryExpression.isReviewedEqualityOperand(
    operand: KtExpression,
    observation: IntellijReadObservation,
): Boolean {
    if (left !== operand && right !== operand) return false
    val token = operationToken
    if (token in IDENTITY_OPERATORS) return true
    if (token !in STRUCTURAL_OPERATORS) return false
    val receiver = left ?: return false
    return observation.observedAnalyze(this) { reviewedEquals(receiver) }
}

private fun KaSession.reviewedEquals(receiver: KtExpression): Boolean {
    val type = receiver.expressionType as? KaClassType ?: return false
    val owner = type.symbol as? KaNamedClassSymbol ?: return false
    if (!owner.hasFixedEquals()) return false
    val selected =
        owner.memberScope
            .callables(Name.identifier("equals"))
            .filterIsInstance<KaNamedFunctionSymbol>()
            .filter { it.valueParameters.size == 1 && it.receiverParameter == null }
            .singleOrNull() ?: return false
    val selectedId = selected.callableId?.asSingleFqName()?.asString() ?: return false
    if (selectedId == ANY_EQUALS) return true
    val ownerName = type.classId.asSingleFqName().asString()
    return selected.origin == KaSymbolOrigin.LIBRARY &&
        ownerName in REVIEWED_EQUALS_OWNERS &&
        selectedId == "$ownerName.equals"
}

/** A final, non-data, non-value class: no subclass or generated member can replace its `equals`. */
private fun KaNamedClassSymbol.hasFixedEquals(): Boolean =
    classKind == KaClassKind.CLASS && modality == KaSymbolModality.FINAL && !isData && !isInline
