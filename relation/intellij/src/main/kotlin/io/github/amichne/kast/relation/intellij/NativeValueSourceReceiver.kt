@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/** A direct source receiver enters its exact compiler receiver formal without asserting a result dependency. */
internal fun nativeValueSourceReceiver(
    source: ValueSite,
    nativeOwner: PsiElement,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<ValueTransfer, ValueFlowUnsupportedCause> {
    val role = source.role as? ValueRole.Receiver ?: return receiverRejected()
    val function =
        when (val value = projection.subject(scope, role.call.callable)) {
            is IntellijRelationSubjectLookup.Found -> value.declaration as? KtNamedFunction
            is IntellijRelationSubjectLookup.Rejected -> null
        } ?: return receiverRejected()
    if (function.bodyExpression == null) return Refinement.Rejected(ValueFlowUnsupportedCause.EXTERNAL_CALL)
    val host =
        when (val value = projection.subject(scope, role.call.enclosing)) {
            is IntellijRelationSubjectLookup.Found -> value.declaration
            is IntellijRelationSubjectLookup.Rejected -> return receiverRejected()
        }
    val expression = host.exactValueElement(role.call.range) ?: return receiverRejected()
    if (expression is KtDestructuringDeclarationEntry) {
        val fresh = nativeDestructuringReceiver(expression, source.executionOwner, nativeOwner, scope, projection)
        if (fresh !is Refinement.Refined || fresh.value != role) return receiverRejected()
        val final =
            projection.observation.observedAnalyze(function) { function.symbol.modality == KaSymbolModality.FINAL }
        if (!final) return receiverRejected()
        return nativeSourceEntry(source, role.call, ValueFormalPosition.Receiver(ValueReceiverKind.EXTENSION))
    }
    val call = expression.asCallbackCall() ?: return receiverRejected()
    val position =
        nativeReceiverPosition(
            role = role,
            range = source.range,
            call = call,
            function = function,
            projection = projection,
        ) ?: return receiverRejected()
    if (position != role.kind) return receiverRejected()
    return nativeSourceEntry(source, role.call, ValueFormalPosition.Receiver(position))
}

internal fun nativeReceiverPosition(
    role: ValueRole.Receiver,
    range: ExactDeclarationTextRange,
    call: KtCallElement,
    function: KtNamedFunction,
    projection: IntellijK2RelationProjection,
): ValueReceiverKind? =
    projection.observation.observedAnalyze(call) {
        val resolved = call.resolveCall() ?: return@observedAnalyze null
        val symbol = resolved.signature.symbol
        if (symbol.psi?.originalElement != function.originalElement || symbol.modality != KaSymbolModality.FINAL)
            return@observedAnalyze null
        val selected =
            when (role.kind) {
                ValueReceiverKind.EXTENSION -> resolved.extensionReceiver
                ValueReceiverKind.DISPATCH -> resolved.dispatchReceiver
            }
        if (selected is KaImplicitReceiverValue)
            return@observedAnalyze role.kind.takeIf {
                range == sourceValueRange(call.valueInvocationExpression())
            }
        val receiver = call.valueInvocationExpression() as? KtQualifiedExpression ?: return@observedAnalyze null
        if (sourceValueRange(receiver.receiverExpression) != range) return@observedAnalyze null
        nativeExplicitReceiverKind(receiver.receiverExpression, resolved.extensionReceiver, resolved.dispatchReceiver)
    }

private fun receiverRejected(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
