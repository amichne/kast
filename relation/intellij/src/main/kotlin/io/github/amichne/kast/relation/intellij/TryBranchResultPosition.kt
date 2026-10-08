package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueCatchBranchIndex
import io.github.amichne.kast.relation.contract.ValueTryBranchAlternative
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtTryExpression

/** Structural candidates only. K2 admission remains in the native value-flow reader. */
internal sealed interface TryBranchResultPosition {
    data class Result(
        val enclosing: KtTryExpression,
        val branch: KtExpression,
        val alternative: ValueTryBranchAlternative,
    ) : TryBranchResultPosition

    data object Discarded : TryBranchResultPosition

    data object Unavailable : TryBranchResultPosition
}

internal fun tryBranchResultPosition(expression: KtExpression): TryBranchResultPosition {
    val block = expression.parent as? KtBlockExpression ?: return TryBranchResultPosition.Unavailable
    return when (val container = block.parent) {
        is KtTryExpression -> tryBodyResult(expression, block, container)
        is KtCatchClause -> catchBodyResult(expression, block, container)
        else -> TryBranchResultPosition.Unavailable
    }
}

private fun tryBodyResult(
    expression: KtExpression,
    block: KtBlockExpression,
    enclosing: KtTryExpression,
): TryBranchResultPosition =
    when {
        enclosing.tryBlock !== block -> TryBranchResultPosition.Unavailable
        block.statements.lastOrNull() !== expression -> TryBranchResultPosition.Discarded
        else -> TryBranchResultPosition.Result(enclosing, block, ValueTryBranchAlternative.TryBody)
    }

private fun catchBodyResult(
    expression: KtExpression,
    block: KtBlockExpression,
    clause: KtCatchClause,
): TryBranchResultPosition {
    val enclosing = clause.parent as? KtTryExpression ?: return TryBranchResultPosition.Unavailable
    if (clause.catchBody !== block) return TryBranchResultPosition.Unavailable
    if (block.statements.lastOrNull() !== expression) return TryBranchResultPosition.Discarded
    val index = enclosing.catchClauses.indexOfFirst { it === clause }
    return when (val admitted = ValueCatchBranchIndex.parse(index)) {
        is Refinement.Refined ->
            TryBranchResultPosition.Result(enclosing, block, ValueTryBranchAlternative.CatchBody(admitted.value))
        is Refinement.Rejected -> TryBranchResultPosition.Unavailable
    }
}
