package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.WorkAllowance
import io.github.amichne.kast.relation.contract.ValueCondition
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteFailure
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtExpression

/** Cast operands and selected results keep distinct normal-completion rules. */
internal class NativeValueTypeOperations(
    private val source: ValueSite,
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
            is NativeValueCastOutcome.Conditional ->
                when (val target = conditionalResult(cast, outcome)) {
                    is Refinement.Refined -> retained.emitTarget(target.value, ValueTransferKind.CAST_RESULT)
                    is Refinement.Rejected ->
                        retained.unresolvedAt(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, cast)
                }
            is NativeValueCastOutcome.Unsupported -> retained.unresolvedAt(outcome.cause, cast)
        }
    }

    private fun conditionalResult(
        cast: KtBinaryExpressionWithTypeRHS,
        outcome: NativeValueCastOutcome.Conditional,
    ): Refinement<ValueSite, ValueSiteFailure> {
        val range = sourceValueRange(cast)
        val fact =
            when (
                val admitted =
                    ValueCondition.CastSuccess.fromCompiler(
                        source = source,
                        useRange = range,
                        kind = outcome.kind,
                        originalType = outcome.originalType,
                        targetType = outcome.targetType,
                    )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        return when (val conditions = source.conditions.requiring(fact)) {
            is Refinement.Rejected -> conditions
            is Refinement.Refined ->
                ValueSite.fromCompiler(
                    enclosing = source.enclosing,
                    range = range,
                    role = ValueRole.ExpressionResult,
                    executionOwner = source.executionOwner,
                    valuePath = source.valuePath,
                    callStack = source.callStack,
                    conditions = conditions.value,
                )
        }
    }
}
