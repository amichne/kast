@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

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
import org.jetbrains.kotlin.analysis.api.resolution.KaReceiverValue
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/** A direct source receiver enters its exact compiler receiver formal without asserting a result dependency. */
internal fun nativeValueSourceReceiver(
    source: ValueSite,
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
    val call =
        when (expression) {
            is KtCallElement -> expression
            is KtQualifiedExpression -> expression.selectorExpression as? KtCallElement
            else -> null
        } ?: return receiverRejected()
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
        matchedReceiverPosition(
            kind = role.kind,
            range = range,
            call = call,
            extension = resolved.extensionReceiver,
            dispatch = resolved.dispatchReceiver,
        )
    }

private fun matchedReceiverPosition(
    kind: ValueReceiverKind,
    range: ExactDeclarationTextRange,
    call: KtCallElement,
    extension: KaReceiverValue?,
    dispatch: KaReceiverValue?,
): ValueReceiverKind? {
    val selected =
        when (kind) {
            ValueReceiverKind.EXTENSION -> extension
            ValueReceiverKind.DISPATCH -> dispatch
        }
    if (selected is KaImplicitReceiverValue)
        return kind.takeIf { range == sourceValueRange(call.valueInvocationExpression()) }
    val receiver = call.valueInvocationExpression() as? KtQualifiedExpression ?: return null
    if (sourceValueRange(receiver.receiverExpression) != range) return null
    return nativeExplicitReceiverKind(receiver.receiverExpression, extension, dispatch)
}

private fun receiverRejected(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
