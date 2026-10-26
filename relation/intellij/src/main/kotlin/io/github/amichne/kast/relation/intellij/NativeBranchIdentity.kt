@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.WorkAllowance
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaSmartCastedReceiverValue
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtThisExpression

internal enum class NativeBranchIdentity {
    EXACT,
    OTHER,
    UNAVAILABLE,
    WORK_LIMIT_REACHED,
}

/** Immutable local aliases keep the original compiler symbol within an explicit depth limit. */
internal fun KaSession.branchExpressionIdentity(
    expression: KtExpression?,
    expected: KaSymbol,
    workAllowance: WorkAllowance,
): NativeBranchIdentity {
    var current = expression
    for (depth in 1..MAX_BRANCH_ALIAS_DEPTH) {
        if (!workAllowance.permits(depth.toLong())) return NativeBranchIdentity.WORK_LIMIT_REACHED
        val reference =
            when (val selected = current) {
                    is KtThisExpression -> selected.instanceReference
                    is KtNameReferenceExpression -> selected
                    else -> return NativeBranchIdentity.UNAVAILABLE
                }
                .references
                .filterIsInstance<KtReference>()
                .singleOrNull() ?: return NativeBranchIdentity.UNAVAILABLE
        val resolved = reference.resolveToSymbol() ?: return NativeBranchIdentity.UNAVAILABLE
        if (resolved == expected) return NativeBranchIdentity.EXACT
        val property = resolved.psi as? KtProperty ?: return NativeBranchIdentity.OTHER
        if (!property.isLocal || property.isVar || property.hasDelegate()) return NativeBranchIdentity.UNAVAILABLE
        current = property.initializer
    }
    return NativeBranchIdentity.UNAVAILABLE
}

internal fun KaSession.branchValueReceiverMatch(
    receiver: KaReceiverValue?,
    expected: KaSymbol,
    workAllowance: WorkAllowance,
): NativeBranchIdentity =
    when (receiver) {
        is KaImplicitReceiverValue ->
            if (receiver.symbol == expected) NativeBranchIdentity.EXACT else NativeBranchIdentity.OTHER
        is KaExplicitReceiverValue -> branchExpressionIdentity(receiver.expression, expected, workAllowance)
        is KaSmartCastedReceiverValue -> branchValueReceiverMatch(receiver.original, expected, workAllowance)
        null -> NativeBranchIdentity.OTHER
    }

private const val MAX_BRANCH_ALIAS_DEPTH = 8
