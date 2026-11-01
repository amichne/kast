package io.github.amichne.kast.relation.intellij

import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.DeclaredCallbackValueBinding
import io.github.amichne.kast.relation.contract.DeclaredCallbackValueFailure
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import io.github.amichne.kast.workspace.intellij.read.call
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtLambdaExpression

/** Invocation and result effects come from the same binary symbol and selected formal. */
internal fun readDeclaredCallbackValueBinding(
    literal: KtFunction,
    enclosing: RelationEndpoint,
    context: IntellijCallbackFlowContext,
): Refinement<DeclaredCallbackValueBinding, ValueFlowUnsupportedCause> =
    context.observation.call(IntellijReadCall.VALUE_DECLARED_CALLBACK_ADMISSION) {
        val result = declaredBinding(literal, enclosing, context)
        context.observation.terminated(
            when (result) {
                is Refinement.Refined -> IntellijReadTermination.VALUE_DECLARED_CALLBACK_ADMITTED
                is Refinement.Rejected -> IntellijReadTermination.VALUE_DECLARED_CALLBACK_UNAVAILABLE
            }
        )
        result
    }

private fun declaredBinding(
    literal: KtFunction,
    enclosing: RelationEndpoint,
    context: IntellijCallbackFlowContext,
): Refinement<DeclaredCallbackValueBinding, ValueFlowUnsupportedCause> {
    val expression =
        literal.parent as? KtLambdaExpression ?: return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
    val call =
        PsiTreeUtil.getParentOfType(expression, KtCallExpression::class.java, true)
            ?: return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
    val selected =
        when (val read = readCallbackDependencyContract(context, call, expression)) {
            is CallbackBindingPreparation.DependencyContract -> read
            is CallbackBindingPreparation.Unavailable ->
                return Refinement.Rejected(
                    when (read.cause) {
                        CallbackInvocationFlowCause.WORK_LIMIT_REACHED -> ValueFlowUnsupportedCause.WORK_LIMIT_REACHED
                        CallbackInvocationFlowCause.TIME_LIMIT_REACHED -> ValueFlowUnsupportedCause.TIME_LIMIT_REACHED
                        else -> ValueFlowUnsupportedCause.NESTED_EXECUTION
                    }
                )
            is CallbackBindingPreparation.Prepared,
            is CallbackBindingPreparation.Direct,
            is CallbackBindingPreparation.DeferredDependency,
            is CallbackBindingPreparation.ContractRejected ->
                return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
        }
    val body = context.anonymous(literal) ?: return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
    return when (
        val binding =
            DeclaredCallbackValueBinding.admit(
                enclosing = enclosing,
                body = body,
                contract = selected.binding,
                resultEffect = selected.resultEffect,
            )
    ) {
        is Refinement.Refined -> binding
        is Refinement.Rejected -> {
            context.observation.terminated(
                when (binding.failure) {
                    DeclaredCallbackValueFailure.BASIS_MISMATCH ->
                        IntellijReadTermination.VALUE_DECLARED_CALLBACK_BASIS_MISMATCH
                    DeclaredCallbackValueFailure.OWNER_MISMATCH ->
                        IntellijReadTermination.VALUE_DECLARED_CALLBACK_OWNER_MISMATCH
                }
            )
            Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
        }
    }
}
