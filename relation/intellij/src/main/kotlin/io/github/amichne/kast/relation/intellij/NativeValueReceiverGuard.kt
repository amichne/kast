@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaSmartCastedReceiverValue
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaIntersectionType
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtWhenConditionIsPattern
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhenExpression

internal sealed interface NativeReceiverGuard {
    data object Direct : NativeReceiverGuard

    data class Subtype(val declaration: PsiNamedElement) : NativeReceiverGuard
}

internal sealed interface NativeReceiverFieldProof {
    data class Matched(val guard: NativeReceiverGuard) : NativeReceiverFieldProof

    data object Unrelated : NativeReceiverFieldProof

    data class Unsupported(val cause: ValueFlowUnsupportedCause) : NativeReceiverFieldProof
}

/** Compiler receiver identity precedes a finite, source-proved type condition. */
internal fun KaSession.receiverFieldGuard(
    function: KtNamedFunction,
    position: ValueFormalPosition.Receiver,
    expression: KtNameReferenceExpression,
    receiver: KaReceiverValue?,
    property: KaKotlinPropertySymbol,
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
    val entry = PsiTreeUtil.getParentOfType(expression, KtWhenEntry::class.java, false)
    if (entry == null)
        return if (receiver is KaSmartCastedReceiverValue) unsupportedReceiver()
        else NativeReceiverFieldProof.Matched(NativeReceiverGuard.Direct)
    return subtypeReceiverGuard(entry, expected, receiver, property)
}

private fun KaSession.subtypeReceiverGuard(
    entry: KtWhenEntry,
    expected: KaSymbol,
    receiver: KaReceiverValue?,
    property: KaKotlinPropertySymbol,
): NativeReceiverFieldProof {
    val condition = entry.conditions.singleOrNull() as? KtWhenConditionIsPattern ?: return unsupportedReceiver()
    if (entry.guard != null || condition.isNegated) return unsupportedReceiver()
    val branch = entry.parent as? KtWhenExpression ?: return unsupportedReceiver()
    if (thisReceiverMatch(branch.subjectExpression, expected) != NativeReceiverMatch.EXACT) return unsupportedReceiver()
    val type = condition.typeReference?.type as? KaClassType ?: return unsupportedReceiver()
    if (property.containingDeclaration?.psi?.originalElement != type.symbol.psi?.originalElement)
        return unsupportedReceiver()
    if (receiver is KaSmartCastedReceiverValue && !receiver.type.hasReceiverClass(type)) return unsupportedReceiver()
    val declaration = type.symbol.psi as? PsiNamedElement ?: return unsupportedReceiver()
    return NativeReceiverFieldProof.Matched(NativeReceiverGuard.Subtype(declaration))
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

private fun org.jetbrains.kotlin.analysis.api.types.KaType.hasReceiverClass(expected: KaClassType): Boolean =
    when (this) {
        is KaClassType -> symbol == expected.symbol
        is KaIntersectionType -> conjuncts.any { it is KaClassType && it.symbol == expected.symbol }
        else -> false
    }

private fun unsupportedReceiver() =
    NativeReceiverFieldProof.Unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
