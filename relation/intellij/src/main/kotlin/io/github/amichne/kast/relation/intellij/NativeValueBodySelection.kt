@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.psi.KtNamedFunction

internal enum class NativeValueBodySelection {
    DIRECT,
    IMPLEMENTATION_INVENTORY,
}

/** A final callable or final dispatch type excludes an overriding runtime body at the exact invocation. */
internal fun nativeValueBodySelection(
    function: KtNamedFunction,
    source: ValueSite,
    callable: RelationEndpoint,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): NativeValueBodySelection {
    val final = projection.observation.observedAnalyze(function) { function.symbol.modality == KaSymbolModality.FINAL }
    if (final) return NativeValueBodySelection.DIRECT
    val invocation = source.boundInvocation(callable) ?: return NativeValueBodySelection.IMPLEMENTATION_INVENTORY
    val host =
        when (val selected = projection.subject(scope, invocation.enclosing)) {
            is IntellijRelationSubjectLookup.Found -> selected.declaration
            is IntellijRelationSubjectLookup.Rejected -> return NativeValueBodySelection.IMPLEMENTATION_INVENTORY
        }
    val syntax =
        host.exactValueElement(invocation.range)?.nativeSelectedValueCallSyntax(function, projection)
            ?: return NativeValueBodySelection.IMPLEMENTATION_INVENTORY
    return projection.observation.observedAnalyze(syntax.element) {
        val call = resolveNativeValueCall(syntax)
        val dispatch =
            if (syntax is NativeValueCallSyntax.StringConversion) syntax.expression.expressionType as? KaClassType
            else call?.dispatchReceiver?.type as? KaClassType
        val selected =
            if (syntax is NativeValueCallSyntax.StringConversion) syntax.function else call?.signature?.symbol?.psi
        val owner = dispatch?.symbol as? KaClassSymbol
        if (selected?.originalElement == function.originalElement && owner?.modality == KaSymbolModality.FINAL) {
            NativeValueBodySelection.DIRECT
        } else {
            NativeValueBodySelection.IMPLEMENTATION_INVENTORY
        }
    }
}

private fun ValueSite.boundInvocation(callable: RelationEndpoint): ValueInvocation? {
    val invocation =
        when (val selected = role) {
            is ValueRole.Argument -> selected.call
            is ValueRole.Receiver -> selected.call
            else -> callStack.frames.lastOrNull()?.invocation
        }
    return invocation?.takeIf { it.callable.valueIdentity == callable.valueIdentity }
}
