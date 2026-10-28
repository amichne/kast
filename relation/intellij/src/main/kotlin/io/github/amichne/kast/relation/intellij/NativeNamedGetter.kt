@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaVariableAccessCall
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.psi.KtProperty

/** Ordinary property reads reuse getter call frames. A property name supplies no field correspondence. */
internal fun nativeNamedGetter(
    syntax: NativeValueCallSyntax.PropertyRead,
    projection: IntellijK2RelationProjection,
): Refinement<KtProperty, ValueFlowUnsupportedCause> =
    projection.observation.observedAnalyze(syntax.expression) {
        if (syntax.expression.selectorExpression !== syntax.reference) return@observedAnalyze unavailableGetter()
        val call = syntax.reference.resolveCall() as? KaVariableAccessCall ?: return@observedAnalyze unavailableGetter()
        val symbol = call.signature.symbol as? KaKotlinPropertySymbol ?: return@observedAnalyze unavailableGetter()
        val receiver = call.dispatchReceiver as? KaExplicitReceiverValue ?: return@observedAnalyze unavailableGetter()
        val property = symbol.psi as? KtProperty ?: return@observedAnalyze unavailableGetter()
        val finalOwner = (symbol.containingDeclaration as? KaClassSymbol)?.modality == KaSymbolModality.FINAL
        if (nativeGetterSelection(call, receiver, syntax) && nativeGetterDeclaration(symbol, property, finalOwner))
            Refinement.Refined(property)
        else unavailableGetter()
    }

internal fun nativeNamedGetterReceiver(
    syntax: NativeValueCallSyntax.PropertyRead,
    execution: ValueExecutionOwner,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<ValueRole.Receiver, ValueFlowUnsupportedCause> {
    val property =
        when (val selected = nativeNamedGetter(syntax, projection)) {
            is Refinement.Refined -> selected.value
            is Refinement.Rejected -> return selected
        }
    val endpoint =
        when (
            val admitted =
                nativeValueCallableEndpoint(
                    declaration = property,
                    enclosing = execution.enclosing,
                    scope = scope,
                    projection = projection,
                )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    return when (
        val invocation =
            ValueInvocation.getterFromCompiler(
                enclosing = execution.enclosing,
                range = sourceValueRange(syntax.expression),
                callable = endpoint,
                executionOwner = execution,
            )
    ) {
        is Refinement.Refined -> Refinement.Refined(ValueRole.Receiver(invocation.value, ValueReceiverKind.DISPATCH))
        is Refinement.Rejected -> unavailableGetter()
    }
}

private fun unavailableGetter(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY)

private fun nativeGetterSelection(
    call: KaVariableAccessCall,
    receiver: KaExplicitReceiverValue,
    syntax: NativeValueCallSyntax.PropertyRead,
): Boolean =
    call.kind is KaVariableAccessCall.Kind.Read &&
        call.extensionReceiver == null &&
        receiver.expression === syntax.expression.receiverExpression

private fun nativeGetterDeclaration(
    symbol: KaKotlinPropertySymbol,
    property: KtProperty,
    finalOwner: Boolean,
): Boolean {
    if (!symbol.isVal || property.hasDelegate() || property.getter?.bodyExpression == null) return false
    return symbol.returnType !is KaErrorType && (finalOwner || symbol.modality == KaSymbolModality.FINAL)
}
