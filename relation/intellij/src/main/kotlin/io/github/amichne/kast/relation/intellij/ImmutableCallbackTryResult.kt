package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtTryExpression

/** Structural candidates only; each candidate still requires NativeTryBranchResult compiler admission. */
internal data class ImmutableCallbackTryResult(
    val expression: KtExpression,
    val position: TryBranchResultPosition.Result,
)

internal fun immutableCallbackTryResults(
    expression: KtTryExpression,
    admitCandidate: () -> Refinement<Unit, CallbackInvocationFlowCause>,
): Refinement<List<ImmutableCallbackTryResult>, CallbackInvocationFlowCause> {
    if (expression.finallyBlock != null) return Refinement.Rejected(CallbackInvocationFlowCause.FINALLY_UNSUPPORTED)
    val results = mutableListOf<ImmutableCallbackTryResult>()
    for (block in sequenceOf(expression.tryBlock) + expression.catchClauses.asSequence().map { it.catchBody }) {
        when (val admitted = admitCandidate()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return admitted
        }
        val result =
            (block as? KtBlockExpression)?.statements?.lastOrNull()
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
        val position =
            tryBranchResultPosition(result) as? TryBranchResultPosition.Result
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
        results += ImmutableCallbackTryResult(result, position)
    }
    return Refinement.Refined(results)
}

/** Preserve finite native rejection identity at the callback boundary. */
internal fun ValueFlowUnsupportedCause.callbackTryFailure(): CallbackInvocationFlowCause =
    when (this) {
        ValueFlowUnsupportedCause.FINALLY_UNSUPPORTED -> CallbackInvocationFlowCause.FINALLY_UNSUPPORTED
        ValueFlowUnsupportedCause.ABRUPT_COMPLETION -> CallbackInvocationFlowCause.ABRUPT_COMPLETION
        ValueFlowUnsupportedCause.WORK_LIMIT_REACHED -> CallbackInvocationFlowCause.WORK_LIMIT_REACHED
        ValueFlowUnsupportedCause.RESULT_LIMIT_REACHED -> CallbackInvocationFlowCause.RESULT_LIMIT_REACHED
        ValueFlowUnsupportedCause.TIME_LIMIT_REACHED -> CallbackInvocationFlowCause.TIME_LIMIT_REACHED
        ValueFlowUnsupportedCause.BYTE_LIMIT_REACHED -> CallbackInvocationFlowCause.BYTE_LIMIT_REACHED
        ValueFlowUnsupportedCause.EXTERNAL_CALL -> CallbackInvocationFlowCause.EXTERNAL_CALLABLE
        ValueFlowUnsupportedCause.OUTSIDE_DOMAIN -> CallbackInvocationFlowCause.OUTSIDE_DOMAIN
        ValueFlowUnsupportedCause.MUTABLE_CONTROL_FLOW -> CallbackInvocationFlowCause.STORED_CALLBACK
        ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE -> CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE
        ValueFlowUnsupportedCause.NESTED_EXECUTION -> CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION
        ValueFlowUnsupportedCause.UNMODELED_CALL,
        ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION,
        ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY,
        ValueFlowUnsupportedCause.UNSUPPORTED_RETURN -> CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY
    }
