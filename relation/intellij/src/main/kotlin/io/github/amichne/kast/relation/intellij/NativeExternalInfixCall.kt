@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.psi.KtExperimentalApi::class,
)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.resolution.KaSingleCall
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression

/** A compiler-selected library call is a frontier, never a guessed argument/result correspondence. */
internal fun nativeExternalInfixCall(expression: KtBinaryExpression, observation: IntellijReadObservation): Boolean =
    if (expression.operationToken == KtTokens.EQ) false
    else
        observation.observedAnalyze(expression) {
            val call = expression.resolveCall() as? KaSingleCall<*, *>
            val symbol = call?.signature?.symbol as? KaNamedFunctionSymbol
            symbol?.origin == KaSymbolOrigin.LIBRARY
        }
