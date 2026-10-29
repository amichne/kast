package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueTransferKind
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList

/** Builtin callback actuals enter their selected supplier; authored calls retain ordinary argument projection. */
internal class NativeValueArgumentFlow(
    private val request: ValueFlowRequest,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val retained: NativeValueFlowTransfers,
    private val allowance: IntellijRelationAllowance,
) {
    fun read(expression: KtExpression, argument: KtValueArgument): Refinement<Unit, ValueFlowRejection> {
        val call =
            when (val container = argument.parent) {
                is KtCallExpression -> container
                is KtValueArgumentList -> container.parent as? KtCallExpression
                else -> null
            }
        if (call != null) {
            when (val binding = nativeCallbackArgumentBinding(call, expression, projection.observation)) {
                is Refinement.Rejected -> {
                    retained.unresolvedAt(binding.failure, argument)
                    return Refinement.Refined(Unit)
                }
                is Refinement.Refined ->
                    when (val selected = binding.value) {
                        is NativeCallbackArgumentBinding.Mapped -> return callback(selected, argument)
                        NativeCallbackArgumentBinding.NotCallback -> Unit
                    }
            }
        }
        ordinary(expression, argument)
        return Refinement.Refined(Unit)
    }

    private fun callback(
        binding: NativeCallbackArgumentBinding.Mapped,
        argument: KtValueArgument,
    ): Refinement<Unit, ValueFlowRejection> {
        val input =
            NativeCallbackArgumentInput(
                source = request.source,
                scope = scope,
                projection = projection,
                admitWork = { allowance.admitCallbackWork(request.budget.resources) },
                expiry = {
                    if (allowance.elapsedLimitReached(request.budget.resources)) CallbackExpiryAdmission.EXPIRED
                    else CallbackExpiryAdmission.CURRENT
                },
            )
        return when (val read = input.read(binding)) {
            is Refinement.Refined -> {
                retained.emitTarget(read.value.target, read.value.kind, read.value.evidence)
                Refinement.Refined(Unit)
            }
            is Refinement.Rejected -> retained.unresolvedCallbackInput(read.failure, argument)
        }
    }

    private fun ordinary(expression: KtExpression, argument: KtValueArgument) {
        when (
            val role =
                nativeValueArgument(
                    expression = expression,
                    argument = argument,
                    scope = scope,
                    projection = projection,
                    executionOwner = request.source.executionOwner,
                )
        ) {
            is Refinement.Refined -> retained.emit(expression, role.value, ValueTransferKind.ARGUMENT)
            is Refinement.Rejected -> {
                val call = (argument.parent as? KtValueArgumentList)?.parent as? KtCallExpression
                if (
                    role.failure == io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause.EXTERNAL_CALL &&
                        call != null
                ) {
                    val context = receiverContext(request, scope, projection, allowance)
                    when (
                        val reviewed =
                            nativeReviewedTupleRole(
                                expression,
                                call.valueInvocationExpression() as KtExpression,
                                request.source.executionOwner,
                                context,
                            )
                    ) {
                        is Refinement.Refined -> retained.emit(expression, reviewed.value, ValueTransferKind.ARGUMENT)
                        is Refinement.Rejected -> retained.unresolvedAt(reviewed.failure, argument)
                    }
                } else retained.unresolvedAt(role.failure, argument)
            }
        }
    }
}
