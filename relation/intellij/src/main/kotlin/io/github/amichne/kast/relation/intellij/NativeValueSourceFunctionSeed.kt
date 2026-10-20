@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.relation.contract.ValueFunctionInvocation
import io.github.amichne.kast.relation.contract.ValueProducerSeedRejection
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

internal sealed interface NativeSeedTarget {
    data class Declared(val endpoint: RelationEndpoint) : NativeSeedTarget

    data class SourceFunction(
        val invocation: ValueFunctionInvocation,
        val property: RevalidatedRelationEndpoint,
        val reference: RelationOccurrence,
    ) : NativeSeedTarget
}

/** The current property supplies the real builtin receiver. This proof establishes no function body or result edge. */
internal fun nativeSourceFunctionSeed(
    expression: NativeSeedExpression,
    enclosing: IntellijValueFlowCompilerAdapter.Prepared.Ready,
    expected: IntellijValueFlowCompilerAdapter.Prepared.Ready,
    selector: SymbolSelector,
): Refinement<NativeSeedTarget.SourceFunction, ValueProducerSeedRejection> =
    enclosing.projection.observation.observedAnalyze(expression.expression) {
        val type = expression.expression.expressionType
        if (type == null || type is KaErrorType)
            return@observedAnalyze Refinement.Rejected(ValueProducerSeedRejection.UNRESOLVED_INVOCATION)
        val resolved =
            expression.call.resolveCall()
                ?: return@observedAnalyze Refinement.Rejected(ValueProducerSeedRejection.UNRESOLVED_INVOCATION)
        val symbol =
            resolved.signature.symbol as? KaNamedFunctionSymbol
                ?: return@observedAnalyze Refinement.Rejected(ValueProducerSeedRejection.UNSUPPORTED_INVOCATION)
        val builtin =
            when (val admitted = nativeBuiltinCallable(symbol)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return@observedAnalyze Refinement.Rejected(ValueProducerSeedRejection.UNSUPPORTED_INVOCATION)
            }
        val receiver =
            resolved.dispatchReceiver as? KaExplicitReceiverValue
                ?: return@observedAnalyze Refinement.Rejected(ValueProducerSeedRejection.UNSUPPORTED_INVOCATION)
        val reference =
            receiver.expression as? KtNameReferenceExpression
                ?: return@observedAnalyze Refinement.Rejected(ValueProducerSeedRejection.UNSUPPORTED_INVOCATION)
        val endpoint =
            when (val admitted = sourceFunctionProperty(reference, expected, enclosing, selector)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return@observedAnalyze admitted
            }
        val invocation =
            when (val admitted = nativeFunctionInvocation(expression.call, builtin, expression.executionOwner)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return@observedAnalyze Refinement.Rejected(ValueProducerSeedRejection.ANCHOR_MISMATCH)
            }
        Refinement.Refined(
            NativeSeedTarget.SourceFunction(
                invocation = invocation,
                property = endpoint,
                reference = RelationOccurrence.fromSource(invocation.enclosing.file, sourceValueRange(reference)),
            )
        )
    }

private fun KaSession.sourceFunctionProperty(
    reference: KtNameReferenceExpression,
    expected: IntellijValueFlowCompilerAdapter.Prepared.Ready,
    enclosing: IntellijValueFlowCompilerAdapter.Prepared.Ready,
    selector: SymbolSelector,
): Refinement<RevalidatedRelationEndpoint, ValueProducerSeedRejection> {
    val property =
        when (val binding = sourceFunctionReference(reference)) {
            is Refinement.Refined -> binding.value
            is Refinement.Rejected -> return binding
        }
    val declaration =
        expected.owner as? PsiNamedElement ?: return Refinement.Rejected(ValueProducerSeedRejection.STALE_CALLABLE)
    if (property != nativeSymbol(declaration)) return Refinement.Rejected(ValueProducerSeedRejection.CALLABLE_MISMATCH)
    if (property.origin != KaSymbolOrigin.SOURCE)
        return Refinement.Rejected(ValueProducerSeedRejection.UNSUPPORTED_INVOCATION)
    if (!property.isVal || property.isDelegated || property.getter?.isNotDefault != false)
        return Refinement.Rejected(ValueProducerSeedRejection.UNSUPPORTED_INVOCATION)
    return projectDeclaration(
        native = enclosing,
        declaration = declaration,
        selector = selector,
        mismatch = ValueProducerSeedRejection.CALLABLE_MISMATCH,
    )
}

private fun KaSession.sourceFunctionReference(
    expression: KtNameReferenceExpression
): Refinement<KaKotlinPropertySymbol, ValueProducerSeedRejection> {
    var selected: KaKotlinPropertySymbol? = null
    for (reference in expression.references.filterIsInstance<KtReference>()) {
        val property =
            when (val binding = reference.resolveToSymbol()) {
                is KaKotlinPropertySymbol -> binding
                is KaValueParameterSymbol -> binding.generatedPrimaryConstructorProperty
                else -> null
            } ?: continue
        if (selected != null && selected != property)
            return Refinement.Rejected(ValueProducerSeedRejection.UNSUPPORTED_INVOCATION)
        selected = property
    }
    return selected?.let { Refinement.Refined(it) }
        ?: Refinement.Rejected(ValueProducerSeedRejection.UNRESOLVED_INVOCATION)
}
