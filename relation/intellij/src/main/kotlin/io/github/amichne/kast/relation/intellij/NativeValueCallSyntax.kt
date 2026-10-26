@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaSingleCall
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/** Both forms retain the exact PSI anchor of one compiler-selected invocation and its result. */
internal sealed interface NativeValueCallSyntax {
    val element: KtElement
    val anchor: KtExpression

    data class Explicit(val call: KtCallElement) : NativeValueCallSyntax {
        override val element: KtElement = call
        override val anchor: KtExpression = call.valueInvocationExpression() as KtExpression
    }

    data class Component(val entry: KtDestructuringDeclarationEntry) : NativeValueCallSyntax {
        override val element: KtElement = entry
        override val anchor: KtExpression = entry
    }
}

internal fun PsiElement.nativeValueCallSyntax(): NativeValueCallSyntax? =
    when (this) {
        is KtCallElement -> NativeValueCallSyntax.Explicit(this)
        is KtQualifiedExpression -> (selectorExpression as? KtCallElement)?.let(NativeValueCallSyntax::Explicit)
        is KtDestructuringDeclarationEntry ->
            if (isNativeSingleComponent()) NativeValueCallSyntax.Component(this) else null
        else -> null
    }

internal fun KaSession.resolveNativeValueCall(syntax: NativeValueCallSyntax): KaSingleCall<*, *>? =
    when (syntax) {
        is NativeValueCallSyntax.Explicit -> syntax.call.resolveCall()
        is NativeValueCallSyntax.Component -> syntax.entry.resolveCall()
    }

internal fun KtDestructuringDeclarationEntry.isNativeComponent(): Boolean =
    name != "_" &&
        (parent as? KtDestructuringDeclaration)?.let { declaration ->
            declaration.nativeDestructuringContext() != NativeDestructuringContext.Unsupported && !declaration.isVar
        } == true

/** Source-call entry and reverse return cannot bypass unsupported evaluation order between authored operators. */
internal fun KtDestructuringDeclarationEntry.isNativeSingleComponent(): Boolean =
    isNativeComponent() && (parent as KtDestructuringDeclaration).entries.count { it.name != "_" } == 1

/** Kotlin binds the compiler-selected component receiver to the local, parameter, or iteration value. */
internal fun KaSession.nativeComponentReceiverKind(entry: KtDestructuringDeclarationEntry): ValueReceiverKind? {
    if (!entry.isNativeComponent()) return null
    val resolved = entry.resolveCall() ?: return null
    val symbol = resolved.signature.symbol as? KaNamedFunctionSymbol ?: return null
    if (!symbol.isOperator || symbol.valueParameters.isNotEmpty()) return null
    return when {
        symbol.receiverParameter != null && resolved.extensionReceiver != null -> ValueReceiverKind.EXTENSION
        symbol.receiverParameter == null && resolved.extensionReceiver == null && resolved.dispatchReceiver != null ->
            ValueReceiverKind.DISPATCH
        else -> null
    }
}
