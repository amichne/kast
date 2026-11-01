package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueFailure
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueExecutionContext
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import org.jetbrains.kotlin.psi.KtFunction

/** Revalidate the binary digest, selected formal and supplier before using the retained result effect. */
internal fun nativeDeclaredCallbackReturn(
    request: ValueFlowRequest,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
    allowance: IntellijRelationAllowance,
    owner: PsiElement,
): NativeValueReturnEffect {
    val source = request.source
    val expected =
        source.executionOwner.context as? ValueExecutionContext.DeclaredCallback ?: return unsupportedDeclaredReturn()
    val literal = owner as? KtFunction ?: return unsupportedDeclaredReturn()
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
    val fresh =
        when (val read = readDeclaredCallbackValueBinding(literal, source.enclosing, context)) {
            is Refinement.Rejected -> return unsupportedDeclaredReturn(read.failure)
            is Refinement.Refined -> read.value
        }
    if (fresh != expected.binding) return unsupportedDeclaredReturn()
    val result = ValueTransfer.declaredCallbackResult(source, fresh)
    context.observation.terminated(
        when (result) {
            is Refinement.Refined -> IntellijReadTermination.VALUE_DECLARED_CALLBACK_RESULT_ADMITTED
            is Refinement.Rejected -> IntellijReadTermination.VALUE_DECLARED_CALLBACK_RESULT_UNSUPPORTED
        }
    )
    if (
        result is Refinement.Refined ||
            result is Refinement.Rejected && result.failure != ValueFlowUnsupportedCause.UNSUPPORTED_RETURN
    )
        return NativeValueReturnEffect.Transfer(result)
    // Read both authorities again before composing their independent claims.
    return when (val reviewed = readReviewedCallbackValueBinding(literal, source.enclosing, context)) {
        is Refinement.Rejected ->
            when (reviewed.failure) {
                ReviewedCallbackValueFailure.CALLABLE_MISMATCH,
                ReviewedCallbackValueFailure.FORMAL_MISMATCH -> NativeValueReturnEffect.Transfer(result)
                else -> unsupportedDeclaredReturn(reviewed.failure.valueFlowCause())
            }
        is Refinement.Refined ->
            NativeValueReturnEffect.Reviewed(ReviewedValueTransfer.callbackResult(source, reviewed.value))
    }
}

private fun unsupportedDeclaredReturn(
    cause: ValueFlowUnsupportedCause = ValueFlowUnsupportedCause.UNSUPPORTED_RETURN
): NativeValueReturnEffect = NativeValueReturnEffect.Transfer(Refinement.Rejected(cause))
