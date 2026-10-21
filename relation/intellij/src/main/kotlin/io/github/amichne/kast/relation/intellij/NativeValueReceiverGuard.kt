@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaSmartCastedReceiverValue
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtWhenEntry

internal sealed interface NativeReceiverFieldProof {
    data object Matched : NativeReceiverFieldProof

    data object Unrelated : NativeReceiverFieldProof

    data class Unsupported(val cause: ValueFlowUnsupportedCause) : NativeReceiverFieldProof
}

/** Plain receiver fields require exact compiler identity without conditional refinement. */
internal fun KaSession.receiverFieldGuard(
    function: KtNamedFunction,
    position: ValueFormalPosition.Receiver,
    expression: KtNameReferenceExpression,
    receiver: KaReceiverValue?,
): NativeReceiverFieldProof {
    val expected =
        when (position.kind) {
            ValueReceiverKind.EXTENSION -> function.symbol.receiverParameter
            ValueReceiverKind.DISPATCH -> function.symbol.containingDeclaration
        } ?: return unsupportedReceiver()
    when (valueReceiverMatch(receiver, expected)) {
        NativeReceiverMatch.OTHER -> return NativeReceiverFieldProof.Unrelated
        NativeReceiverMatch.UNAVAILABLE -> return unsupportedReceiver()
        NativeReceiverMatch.EXACT -> Unit
    }
    if (
        receiver is KaSmartCastedReceiverValue ||
            PsiTreeUtil.getParentOfType(expression, KtWhenEntry::class.java, false) != null
    )
        return unsupportedReceiver()
    return NativeReceiverFieldProof.Matched
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
