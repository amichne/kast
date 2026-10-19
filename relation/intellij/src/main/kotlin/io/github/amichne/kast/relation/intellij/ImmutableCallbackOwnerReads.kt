package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import org.jetbrains.kotlin.psi.KtFunction

/** Alias transfers preserve source identity; terminal uses obtain their actual owner's execution proof. */
internal fun ImmutableCallbackEnumeration.readOwner(
    expression: PsiElement,
    owner: RelationCallableBody,
    enclosing: RelationEndpoint.Resolved,
): Refinement<Unit, CallbackInvocationFlowFailure> {
    val anonymous =
        when (owner) {
            is RelationCallableBody.Named -> return Refinement.Refined(Unit)
            is RelationCallableBody.Anonymous -> owner
        }
    obligations += CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION
    if (owners.any { it.body == anonymous }) return Refinement.Refined(Unit)
    val literal =
        (expression.nearestDeclaration() as? ContainingDeclaration.Deferred)?.boundary as? KtFunction
            ?: return obligation(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
    val binding =
        when (val read = IntellijCallbackOwnerBindingReader(context, summaries).read(literal, anonymous, enclosing)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return read
        }
    obligations += binding.obligations
    when (val retained = summaries.retention.admitOwner(binding)) {
        is Refinement.Refined -> owners += binding
        is Refinement.Rejected -> {
            obligations += retained.failure
            stopped = true
        }
    }
    return Refinement.Refined(Unit)
}
