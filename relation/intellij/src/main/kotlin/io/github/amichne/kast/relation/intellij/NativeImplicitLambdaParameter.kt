@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaAnonymousFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/** The symbol and its containing anonymous function, rather than a synthetic PSI range, prove this formal. */
internal fun KaSession.nativeImplicitLambdaParameter(
    literal: KtFunctionLiteral,
    position: ValueArgumentPosition,
): KaValueParameterSymbol? {
    if (literal.valueParameters.isNotEmpty()) return null
    val owner = literal.symbol as? KaAnonymousFunctionSymbol ?: return null
    if (owner.receiverParameter != null || owner.contextReceivers.isNotEmpty()) return null
    val parameter = owner.valueParameters.getOrNull(position.value) ?: return null
    return parameter.takeIf { it.isImplicitLambdaParameter && it.containingDeclaration == owner }
}

/** Candidate spelling only narrows enumeration; one analysis session confirms both parameter and owner identity. */
internal fun confirmImplicitLambdaParameterReference(
    expression: KtNameReferenceExpression,
    literal: KtFunctionLiteral,
    position: ValueArgumentPosition,
    observation: IntellijReadObservation,
): LocalBindingReferenceConfirmation =
    observation.observedAnalyze(expression) {
        val expected =
            nativeImplicitLambdaParameter(literal, position)
                ?: return@observedAnalyze LocalBindingReferenceConfirmation.UNRESOLVED
        val reference = expression.references.filterIsInstance<KtReference>().singleOrNull()
        val symbol = reference?.resolveToSymbol() ?: return@observedAnalyze LocalBindingReferenceConfirmation.UNRESOLVED
        val resolved =
            symbol as? KaValueParameterSymbol ?: return@observedAnalyze LocalBindingReferenceConfirmation.OTHER_BINDING
        if (
            resolved.isImplicitLambdaParameter &&
                resolved == expected &&
                resolved.containingDeclaration == expected.containingDeclaration
        )
            LocalBindingReferenceConfirmation.EXACT_BINDING
        else LocalBindingReferenceConfirmation.OTHER_BINDING
    }
