@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaSingleCall
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/** Both forms retain the exact PSI anchor of one compiler-selected invocation and its result. */
internal sealed interface NativeValueCallSyntax {
    val element: KtElement
    val anchor: KtExpression

    data class StringConversion private constructor(val expression: KtExpression, val function: KtNamedFunction) :
        NativeValueCallSyntax {
        override val element: KtElement = expression
        override val anchor: KtExpression = expression

        companion object {
            fun select(
                expression: KtExpression,
                expected: KtNamedFunction,
                projection: IntellijK2RelationProjection,
            ): StringConversion? {
                val selected = nativeStringConversionDeclaration(expression, projection)
                return if (selected is Refinement.Refined && selected.value.originalElement == expected.originalElement)
                    StringConversion(expression, expected)
                else null
            }
        }
    }

    data class Explicit(val call: KtCallElement) : NativeValueCallSyntax {
        override val element: KtElement = call
        override val anchor: KtExpression = call.valueInvocationExpression() as KtExpression
    }

    data class PropertyRead(val expression: KtDotQualifiedExpression, val reference: KtNameReferenceExpression) :
        NativeValueCallSyntax {
        override val element: KtElement = expression
        override val anchor: KtExpression = expression
    }

    data class Loop(val loop: KtForExpression, val function: KtNamedFunction) : NativeValueCallSyntax {
        override val element: KtElement = loop
        override val anchor: KtExpression = loop
    }

    data class Component(val entry: KtDestructuringDeclarationEntry) : NativeValueCallSyntax {
        override val element: KtElement = entry
        override val anchor: KtExpression = entry

        fun admit(
            enclosing: RelationEndpoint,
            scope: CompiledRelationScope,
            projection: IntellijK2RelationProjection,
            permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
        ): Refinement<NativeDestructuringAdmission, ValueFlowUnsupportedCause> =
            when (val declaration = entry.parent) {
                is KtDestructuringDeclaration ->
                    NativeDestructuringAdmission.read(
                        declaration = declaration,
                        enclosing = enclosing,
                        scope = scope,
                        projection = projection,
                        permit = permit,
                    )
                else -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            }
    }
}

/** Syntax identifies candidates only; component entry and return must consume the shared sibling admission. */
internal fun PsiElement.nativeValueCallSyntax(expected: KtNamedFunction? = null): NativeValueCallSyntax? =
    when (this) {
        is KtForExpression -> expected?.let { NativeValueCallSyntax.Loop(this, it) }
        is KtCallElement -> NativeValueCallSyntax.Explicit(this)
        is KtQualifiedExpression ->
            (selectorExpression as? KtCallElement)?.let(NativeValueCallSyntax::Explicit)
                ?: if (this is KtDotQualifiedExpression && selectorExpression is KtNameReferenceExpression)
                    (selectorExpression as? KtNameReferenceExpression)?.let {
                        NativeValueCallSyntax.PropertyRead(this, it)
                    }
                else null
        is KtDestructuringDeclarationEntry -> if (isNativeComponent()) NativeValueCallSyntax.Component(this) else null
        else -> null
    }

internal fun KaSession.resolveNativeValueCall(syntax: NativeValueCallSyntax): KaSingleCall<*, *>? =
    when (syntax) {
        is NativeValueCallSyntax.StringConversion -> null
        is NativeValueCallSyntax.Explicit -> syntax.call.resolveCall()
        is NativeValueCallSyntax.PropertyRead -> syntax.reference.resolveCall()
        is NativeValueCallSyntax.Component -> syntax.entry.resolveCall()
        is NativeValueCallSyntax.Loop -> {
            val calls = syntax.loop.resolveCall()
            listOfNotNull(calls?.iteratorCall, calls?.hasNextCall, calls?.nextCall).singleOrNull {
                it.signature.symbol.psi?.originalElement == syntax.function.originalElement
            }
        }
    }

internal fun KtDestructuringDeclarationEntry.isNativeComponent(): Boolean =
    (name != "_" || hasInitializer()) &&
        (parent as? KtDestructuringDeclaration)?.let { declaration ->
            declaration.nativeDestructuringContext() != NativeDestructuringContext.Unsupported
        } == true

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

/** A type-member selection distinguishes implicit conversion from a call that already returns String. */
internal fun PsiElement.nativeSelectedValueCallSyntax(
    expected: KtNamedFunction?,
    projection: IntellijK2RelationProjection,
): NativeValueCallSyntax? {
    if (
        this is KtExpression &&
            parent is org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression &&
            expected?.name == "toString"
    ) {
        val selected = NativeValueCallSyntax.StringConversion.select(this, expected, projection)
        if (selected != null) return selected
    }
    return nativeValueCallSyntax(expected)
}
