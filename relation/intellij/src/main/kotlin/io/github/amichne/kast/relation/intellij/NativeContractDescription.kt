@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Compiler-admitted contract descriptions declare effects without executing their builder references. */
internal fun nativeContractDescriptionReference(
    expression: KtExpression,
    function: KtFunction,
    observation: IntellijReadObservation,
): Boolean {
    val named = function as? KtNamedFunction ?: return false
    val body = named.bodyExpression as? KtBlockExpression ?: return false
    val first = body.statements.firstOrNull() as? KtCallExpression ?: return false
    if (!PsiTreeUtil.isAncestor(first, expression, true)) return false
    return observation.observedAnalyze(first) {
        val symbol = first.resolveCall()?.signature?.symbol as? KaNamedFunctionSymbol
        val declared = named.symbol as? KaNamedFunctionSymbol
        symbol?.callableId?.asSingleFqName()?.asString() == "kotlin.contracts.contract" &&
            declared?.contractEffects?.isNotEmpty() == true
    }
}
