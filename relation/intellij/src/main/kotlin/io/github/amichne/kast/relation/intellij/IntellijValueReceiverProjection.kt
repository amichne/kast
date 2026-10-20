@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaSmartCastedReceiverValue
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/** A receiver arrival establishes no relationship with the selected call's result. */
internal fun nativeValueReceiver(
    expression: KtExpression,
    qualified: KtQualifiedExpression,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
    executionOwner: ValueExecutionOwner,
): Refinement<ValueRole.Receiver, ValueFlowUnsupportedCause> {
    val enclosing = executionOwner.enclosing
    if (qualified.receiverExpression !== expression)
        return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    val call =
        qualified.selectorExpression as? KtCallElement
            ?: return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    val target =
        projection.observation.observedAnalyze(call) {
            val resolved = call.resolveCall() ?: return@observedAnalyze null
            val declaration =
                (resolved.signature.symbol as? KaNamedFunctionSymbol)?.psi as? PsiNamedElement
                    ?: return@observedAnalyze null
            val kind =
                nativeExplicitReceiverKind(expression, resolved.extensionReceiver, resolved.dispatchReceiver)
                    ?: return@observedAnalyze null
            NativeValueReceiverTarget(declaration, kind)
        } ?: return Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
    val endpoint =
        when (
            val admitted =
                nativeValueCallableEndpoint(
                    declaration = target.declaration,
                    enclosing = enclosing,
                    scope = scope,
                    projection = projection,
                )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val range = sourceValueRange(qualified)
    return when (
        val admitted =
            ValueInvocation.fromCompiler(
                enclosing = enclosing,
                range = range,
                callable = endpoint,
                executionOwner = executionOwner,
            )
    ) {
        is Refinement.Refined -> Refinement.Refined(ValueRole.Receiver(admitted.value, target.kind))
        is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
    }
}

private data class NativeValueReceiverTarget(val declaration: PsiNamedElement, val kind: ValueReceiverKind)

internal fun nativeExplicitReceiverKind(
    expression: KtExpression,
    extension: KaReceiverValue?,
    dispatch: KaReceiverValue?,
): ValueReceiverKind? {
    val extensionMatches = extension.matchesExplicit(expression)
    val dispatchMatches = dispatch.matchesExplicit(expression)
    return when {
        extensionMatches && !dispatchMatches -> ValueReceiverKind.EXTENSION
        dispatchMatches && !extensionMatches -> ValueReceiverKind.DISPATCH
        else -> null
    }
}

private fun KaReceiverValue?.matchesExplicit(expected: KtExpression): Boolean =
    when (this) {
        is KaExplicitReceiverValue -> expression.originalElement == expected.originalElement
        is KaSmartCastedReceiverValue -> original.matchesExplicit(expected)
        is KaImplicitReceiverValue,
        null -> false
    }
