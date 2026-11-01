@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueConditions
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueRole
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaSmartCastedReceiverValue
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtThisExpression

internal sealed interface NativeReceiverFieldProof {
    data class Matched(val conditions: ValueConditions) : NativeReceiverFieldProof

    data object Unrelated : NativeReceiverFieldProof

    data class Unsupported(val cause: ValueFlowUnsupportedCause) : NativeReceiverFieldProof
}

/** Receiver fields preserve compiler identity and explicit branch compatibility. */
internal fun KaSession.receiverFieldGuard(
    function: KtFunction,
    expression: KtNameReferenceExpression,
    receiver: KaReceiverValue?,
    request: ValueFlowRequest,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): NativeReceiverFieldProof {
    val source = request.source
    val workAllowance = request.budget.resources.workAllowance
    val position =
        (source.role as? ValueRole.Formal)?.position as? ValueFormalPosition.Receiver ?: return unsupportedReceiver()
    val expected = nativeValueReceiverSymbol(function, position.kind) ?: return unsupportedReceiver()
    when (branchValueReceiverMatch(receiver, expected, workAllowance)) {
        NativeBranchIdentity.OTHER -> return NativeReceiverFieldProof.Unrelated
        NativeBranchIdentity.UNAVAILABLE -> return unsupportedReceiver()
        NativeBranchIdentity.EXACT -> Unit
        NativeBranchIdentity.WORK_LIMIT_REACHED ->
            return NativeReceiverFieldProof.Unsupported(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED)
    }
    return when (branchAdmission(source, expression, expected, scope, projection, workAllowance)) {
        NativeValueBranchAdmission.ADMITTED -> NativeReceiverFieldProof.Matched(source.conditions)
        NativeValueBranchAdmission.NOT_APPLICABLE ->
            if (receiver is KaSmartCastedReceiverValue)
                stableReceiverFieldProof(expression, receiver, request, position.kind)
            else NativeReceiverFieldProof.Matched(source.conditions)
        NativeValueBranchAdmission.EXCLUDED -> NativeReceiverFieldProof.Unrelated
        NativeValueBranchAdmission.UNPROVEN ->
            if (receiver is KaSmartCastedReceiverValue)
                stableReceiverFieldProof(expression, receiver, request, position.kind)
            else unsupportedReceiver()
        NativeValueBranchAdmission.WORK_LIMIT_REACHED ->
            NativeReceiverFieldProof.Unsupported(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED)
    }
}

internal enum class NativeReceiverMatch {
    EXACT,
    OTHER,
    UNAVAILABLE,
}

internal fun KaSession.valueReceiverMatch(receiver: KaReceiverValue?, expected: KaSymbol): NativeReceiverMatch =
    when (receiver) {
        is KaImplicitReceiverValue ->
            if (receiver.symbol == expected) NativeReceiverMatch.EXACT else NativeReceiverMatch.OTHER
        is KaExplicitReceiverValue -> thisReceiverMatch(receiver.expression, expected)
        is KaSmartCastedReceiverValue -> valueReceiverMatch(receiver.original, expected)
        null -> NativeReceiverMatch.OTHER
    }

private fun KaSession.thisReceiverMatch(expression: KtExpression?, expected: KaSymbol): NativeReceiverMatch {
    val reference =
        when (expression) {
                is KtThisExpression -> expression.instanceReference
                is KtNameReferenceExpression -> expression
                else -> return NativeReceiverMatch.UNAVAILABLE
            }
            .references
            .filterIsInstance<KtReference>()
            .singleOrNull() ?: return NativeReceiverMatch.UNAVAILABLE
    val resolved = reference.resolveToSymbol() ?: return NativeReceiverMatch.UNAVAILABLE
    return if (resolved == expected) NativeReceiverMatch.EXACT else NativeReceiverMatch.OTHER
}

private fun unsupportedReceiver() =
    NativeReceiverFieldProof.Unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
