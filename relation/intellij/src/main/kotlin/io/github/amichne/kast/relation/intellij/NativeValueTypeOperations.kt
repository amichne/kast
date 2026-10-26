package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.WorkAllowance
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtExpression

/** Cast operands and selected results keep distinct normal-completion rules. */
internal class NativeValueTypeOperations(
    private val observation: IntellijReadObservation,
    private val allowance: WorkAllowance,
    private val retained: NativeValueFlowTransfers,
) {
    fun readResult(
        expression: KtExpression,
        permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
        consume: (KtExpression) -> Unit,
    ) {
        when (val outcome = nativeValueResultCompletion(expression, observation, allowance, permit)) {
            is Refinement.Refined -> consume(expression)
            is Refinement.Rejected -> retained.unresolvedAt(outcome.failure, expression)
        }
    }

    fun readOperand(expression: KtExpression, cast: KtBinaryExpressionWithTypeRHS, consume: (KtExpression) -> Unit) {
        if (cast.left !== expression) {
            retained.unresolvedAt(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, cast)
            return
        }
        when (val outcome = nativeValueCastOutcome(cast, observation, allowance)) {
            NativeValueCastOutcome.Preserved -> consume(cast)
            NativeValueCastOutcome.NullResult -> Unit
            is NativeValueCastOutcome.Unsupported -> retained.unresolvedAt(outcome.cause, cast)
        }
    }
}
