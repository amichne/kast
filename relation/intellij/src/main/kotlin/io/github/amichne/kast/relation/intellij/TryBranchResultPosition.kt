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

internal fun tryBranchResultPosition(expression: KtExpression): TryBranchResultPosition =
    when (val block = expression.parent) {
        is KtBlockExpression ->
            when (val container = block.parent) {
                is KtTryExpression ->
                    if (container.tryBlock !== block) TryBranchResultPosition.Unavailable
                    else if (block.statements.lastOrNull() !== expression) TryBranchResultPosition.Discarded
                    else TryBranchResultPosition.Result(container, block, ValueTryBranchAlternative.TryBody)
                is KtCatchClause -> {
                    val enclosing = container.parent as? KtTryExpression
                    val index = enclosing?.catchClauses?.indexOfFirst { it === container } ?: -1
                    if (enclosing == null || index < 0 || container.catchBody !== block)
                        TryBranchResultPosition.Unavailable
                    else if (block.statements.lastOrNull() !== expression) TryBranchResultPosition.Discarded
                    else
                        when (val admitted = ValueCatchBranchIndex.parse(index)) {
                            is Refinement.Refined ->
                                TryBranchResultPosition.Result(
                                    enclosing,
                                    block,
                                    ValueTryBranchAlternative.CatchBody(admitted.value),
                                )
                            is Refinement.Rejected -> TryBranchResultPosition.Unavailable
                        }
                }
                else -> TryBranchResultPosition.Unavailable
            }
        else -> TryBranchResultPosition.Unavailable
    }
