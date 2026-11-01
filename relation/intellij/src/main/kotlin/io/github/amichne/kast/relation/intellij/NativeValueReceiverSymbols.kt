@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueReceiverKind
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Anonymous receiver bodies have an extension slot, never the enclosing class dispatch slot. */
internal fun KaSession.nativeValueReceiverSymbol(function: KtFunction, kind: ValueReceiverKind): KaSymbol? =
    when (function) {
        is KtNamedFunction ->
            when (kind) {
                ValueReceiverKind.EXTENSION -> function.symbol.receiverParameter
                ValueReceiverKind.DISPATCH -> function.symbol.containingDeclaration
            }
        is KtFunctionLiteral ->
            when (kind) {
                ValueReceiverKind.EXTENSION -> function.symbol.receiverParameter
                ValueReceiverKind.DISPATCH -> null
            }
        else -> null
    }
