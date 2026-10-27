package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
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
    val result =
        when (val fresh = readDeclaredCallbackValueBinding(literal, source.enclosing, context)) {
            is Refinement.Rejected -> fresh
            is Refinement.Refined ->
                if (fresh.value != expected.binding) Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
                else ValueTransfer.declaredCallbackResult(source, fresh.value)
        }
    context.observation.terminated(
        when (result) {
            is Refinement.Refined -> IntellijReadTermination.VALUE_DECLARED_CALLBACK_RESULT_ADMITTED
            is Refinement.Rejected -> IntellijReadTermination.VALUE_DECLARED_CALLBACK_RESULT_UNSUPPORTED
        }
    )
    return NativeValueReturnEffect.Transfer(result)
}

private fun unsupportedDeclaredReturn(
    cause: ValueFlowUnsupportedCause = ValueFlowUnsupportedCause.UNSUPPORTED_RETURN
): NativeValueReturnEffect = NativeValueReturnEffect.Transfer(Refinement.Rejected(cause))
