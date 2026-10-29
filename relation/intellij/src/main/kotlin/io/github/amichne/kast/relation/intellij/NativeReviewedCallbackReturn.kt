package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueExecutionContext
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import org.jetbrains.kotlin.psi.KtFunction

internal fun nativeReviewedCallbackReturn(
    request: ValueFlowRequest,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
    allowance: IntellijRelationAllowance,
    owner: PsiElement,
): NativeValueReturnEffect {
    val source = request.source
    val expected =
        source.executionOwner.context as? ValueExecutionContext.ReviewedCallback ?: return unsupportedReviewedReturn()
    val literal = owner as? KtFunction ?: return unsupportedReviewedReturn()
    val context =
        IntellijCallbackFlowContext(
            scope,
            projection,
            { allowance.admitCallbackWork(request.budget.resources) },
            projection.observation,
            {
                if (allowance.elapsedLimitReached(request.budget.resources)) CallbackExpiryAdmission.EXPIRED
                else CallbackExpiryAdmission.CURRENT
            },
        )
    return when (val fresh = readReviewedCallbackValueBinding(literal, source.enclosing, context)) {
        is Refinement.Rejected -> unsupportedReviewedReturn(fresh.failure.valueFlowCause())
        is Refinement.Refined ->
            if (fresh.value != expected.binding) unsupportedReviewedReturn()
            else
                NativeValueReturnEffect.Reviewed(
                    if (
                        fresh.value.selected.operation ==
                            io.github.amichne.kast.relation.contract.ReviewedLibraryOperation.ITERABLE_ASSOCIATE
                    )
                        nativeReviewedAssociationReturn(literal, source, fresh.value, context)
                    else ReviewedValueTransfer.callbackResult(source, fresh.value)
                )
    }
}

private fun unsupportedReviewedReturn(
    cause: ValueFlowUnsupportedCause = ValueFlowUnsupportedCause.UNSUPPORTED_RETURN
): NativeValueReturnEffect = NativeValueReturnEffect.Transfer(Refinement.Rejected(cause))
