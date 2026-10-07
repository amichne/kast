package io.github.amichne.kast.relation.intellij

import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure
import io.github.amichne.kast.relation.contract.ImmutableCallbackInvocationUse
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueTransferKind
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty

/** Compiler-confirmed local alias reads preserve ordered binding and read transports. */
internal fun ImmutableCallbackEnumeration.property(
    item: ImmutableCallbackPending,
    value: KtExpression,
    parent: KtProperty,
    endpoint: RelationEndpoint.Resolved,
    owner: ContainingDeclaration.Found,
): Refinement<Unit, CallbackInvocationFlowFailure> {
    val bound =
        when (val admitted = bindLocal(item, value, parent, endpoint)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return obligation(admitted.failure)
        }
    var matched = false
    ReferencesSearch.search(parent, LocalSearchScope(owner.declaration))
        .forEach(
            Processor { reference ->
                if (stopped) return@Processor false
                if (admitLocalReference() is Refinement.Rejected) return@Processor false
                if (
                    localReference(
                        reference,
                        parent,
                        ImmutableCallbackPending(item.expression, bound, item.visited),
                        endpoint,
                    ) == LocalBindingReferenceConfirmation.EXACT_BINDING
                )
                    matched = true
                !stopped
            }
        )
    if (!matched && !stopped) retain(ImmutableCallbackInvocationUse.Unused(bound))

    return Refinement.Refined(Unit)
}

private fun ImmutableCallbackEnumeration.bindLocal(
    item: ImmutableCallbackPending,
    value: KtExpression,
    property: KtProperty,
    endpoint: RelationEndpoint.Resolved,
): Refinement<io.github.amichne.kast.relation.contract.ImmutableCallbackValue, CallbackInvocationFlowCause> {
    if (property.initializer !== value || !property.isImmutableLocal())
        return Refinement.Rejected(CallbackInvocationFlowCause.STORED_CALLBACK)
    val binding =
        context.site(property, endpoint, ValueRole.LocalBinding)
            ?: return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
    return transport(item.value, binding, ValueTransferKind.LOCAL_BINDING)
}

internal fun ImmutableCallbackEnumeration.admitLocalReference(): Refinement<Unit, CallbackInvocationFlowCause> =
    when (val permitted = context.permit()) {
        is Refinement.Refined -> permitted
        is Refinement.Rejected -> {
            obligations += permitted.failure
            stopped = true
            permitted
        }
    }

private fun KtProperty.isImmutableLocal(): Boolean = isLocal && !isVar && !hasDelegate()

internal fun ImmutableCallbackEnumeration.localReference(
    reference: com.intellij.psi.PsiReference,
    property: KtProperty,
    item: ImmutableCallbackPending,
    endpoint: RelationEndpoint.Resolved,
): LocalBindingReferenceConfirmation {
    val native = reference as? KtReference
    val read = native?.element as? KtNameReferenceExpression
    if (native == null || read == null) {
        obligations += CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE
        return LocalBindingReferenceConfirmation.UNRESOLVED
    }
    when (val confirmed = confirmLocalBindingReference(native, property)) {
        LocalBindingReferenceConfirmation.OTHER_BINDING -> return confirmed
        LocalBindingReferenceConfirmation.UNRESOLVED -> {
            obligations += CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE
            return confirmed
        }
        LocalBindingReferenceConfirmation.EXACT_BINDING -> Unit
    }
    if (context.owner(read)?.compilerIdentity != endpoint.compilerIdentity)
        obligations += CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION
    else {
        val site = context.site(read, endpoint, ValueRole.LocalRead)
        if (site == null) obligations += CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE
        else schedule(item, read, site, ValueTransferKind.LOCAL_READ)
    }
    return LocalBindingReferenceConfirmation.EXACT_BINDING
}
