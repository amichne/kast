package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import org.jetbrains.kotlin.psi.KtFunction

/** Bounded native proof attached to callback observations without changing named-call ownership. */
internal fun readCallbackInvocationFlow(
    boundary: PsiElement,
    lexicalOwner: CompilerGroundedSymbolEvidence,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
    admitWork: () -> CallbackWorkAdmission,
): CallbackInvocationFlowRead {
    val context = IntellijCallbackFlowContext(scope, projection, admitWork)
    when (val allowed = context.permit()) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return CallbackInvocationFlowRead.Unavailable(allowed.failure)
    }
    val literal =
        boundary as? KtFunction
            ?: return CallbackInvocationFlowRead.Unavailable(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
    val body =
        context.anonymous(literal)
            ?: return CallbackInvocationFlowRead.Unavailable(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
    return when (val prepared = IntellijCallbackBindingReader(context, lexicalOwner).prepare(literal)) {
        is CallbackBindingPreparation.Unavailable ->
            unavailableCallbackSupply(context, literal, body, lexicalOwner, prepared.cause)
        is CallbackBindingPreparation.ContractRejected -> CallbackInvocationFlowRead.ContractRejected(prepared.cause)
        is CallbackBindingPreparation.Direct -> readDirectCallbackFlow(context, prepared, body, lexicalOwner)
        is CallbackBindingPreparation.Prepared -> IntellijCallbackFlowScan(context, prepared.value, body).read()
    }
}
