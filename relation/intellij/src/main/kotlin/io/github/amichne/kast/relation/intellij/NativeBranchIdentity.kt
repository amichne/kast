@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.WorkAllowance
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaSmartCastedReceiverValue
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
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
        current =
            when (val step = branchAliasStep(current, expected, workAllowance)) {
                is NativeBranchAlias.Next -> step.expression
                is NativeBranchAlias.Resolved -> return step.identity
            }
    }
    return NativeBranchIdentity.UNAVAILABLE
}

private fun KaSession.branchAliasStep(
    expression: KtExpression?,
    expected: KaSymbol,
    workAllowance: WorkAllowance,
): NativeBranchAlias =
    when (expression) {
        is KtParenthesizedExpression -> nextBranchAlias(expression.expression)
        is KtBinaryExpressionWithTypeRHS ->
            when (val outcome = nativeValueCastOutcome(expression, workAllowance)) {
                NativeValueCastOutcome.Preserved -> NativeBranchAlias.Next(expression.left)
                NativeValueCastOutcome.NullResult -> NativeBranchAlias.Resolved(NativeBranchIdentity.OTHER)
                is NativeValueCastOutcome.Conditional -> NativeBranchAlias.Resolved(NativeBranchIdentity.UNAVAILABLE)
                is NativeValueCastOutcome.Unsupported ->
                    NativeBranchAlias.Resolved(
                        if (outcome.cause == ValueFlowUnsupportedCause.WORK_LIMIT_REACHED)
                            NativeBranchIdentity.WORK_LIMIT_REACHED
                        else NativeBranchIdentity.UNAVAILABLE
                    )
            }
        else -> branchReferenceAlias(expression, expected)
    }

private fun KaSession.branchReferenceAlias(expression: KtExpression?, expected: KaSymbol): NativeBranchAlias {
    val reference =
        when (expression) {
                is KtThisExpression -> expression.instanceReference
                is KtNameReferenceExpression -> expression
                else -> return NativeBranchAlias.Resolved(NativeBranchIdentity.UNAVAILABLE)
            }
            .references
            .filterIsInstance<KtReference>()
            .singleOrNull()
    val resolved = reference?.resolveToSymbol()
    return when {
        resolved == null -> NativeBranchAlias.Resolved(NativeBranchIdentity.UNAVAILABLE)
        resolved == expected -> NativeBranchAlias.Resolved(NativeBranchIdentity.EXACT)
        else -> {
            val property = resolved.psi as? KtProperty
            when {
                property == null -> NativeBranchAlias.Resolved(NativeBranchIdentity.OTHER)
                !property.isLocal || property.isVar || property.hasDelegate() ->
                    NativeBranchAlias.Resolved(NativeBranchIdentity.UNAVAILABLE)
                else -> nextBranchAlias(property.initializer)
            }
        }
    }
}

private fun nextBranchAlias(expression: KtExpression?): NativeBranchAlias =
    if (expression == null) NativeBranchAlias.Resolved(NativeBranchIdentity.UNAVAILABLE)
    else NativeBranchAlias.Next(expression)

private sealed interface NativeBranchAlias {
    data class Next(val expression: KtExpression) : NativeBranchAlias

    data class Resolved(val identity: NativeBranchIdentity) : NativeBranchAlias
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
