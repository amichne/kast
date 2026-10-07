package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

sealed interface QueryCallbackReferenceReceiverDocument {
    data object Absent : QueryCallbackReferenceReceiverDocument

    data object Unbound : QueryCallbackReferenceReceiverDocument

    data class Bound(val occurrence: RelationOccurrenceDocument) : QueryCallbackReferenceReceiverDocument

    data class Implicit(val declaration: QueryCallbackCallableDocument) : QueryCallbackReferenceReceiverDocument
}

sealed interface QueryNamedCallbackReferenceFlowDocument {
    data class Immutable(val flow: QueryImmutableCallbackFlowDocument) : QueryNamedCallbackReferenceFlowDocument

    data class Supplied(
        val binding: QueryCallbackBindingDocument.Bound,
        val invocations: BoundedProtocolList<QueryCallbackInvocationDocument>,
        val forwarding: QueryCallbackForwardingEvidenceDocument,
    ) : QueryNamedCallbackReferenceFlowDocument

    data class Direct(val binding: QueryCallbackBindingDocument.Direct) : QueryNamedCallbackReferenceFlowDocument

    data class Unavailable(val obligations: QueryCallbackGraphObligationsDocument) :
        QueryNamedCallbackReferenceFlowDocument
}

data class QueryNamedCallbackReferenceDocument(
    val target: QueryCallbackCallableDocument,
    val dispatchReceiver: QueryCallbackReferenceReceiverDocument,
    val extensionReceiver: QueryCallbackReferenceReceiverDocument,
    val flow: QueryNamedCallbackReferenceFlowDocument,
) {
    internal fun admits(occurrence: RelationOccurrenceDocument, lexicalOwner: QueryCallbackCallableDocument): Boolean {
        if (!target.validCallable()) return false
        if (!listOf(dispatchReceiver, extensionReceiver).all { it.admits(occurrence) }) return false
        return when (val proof = flow) {
            is QueryNamedCallbackReferenceFlowDocument.Immutable ->
                matchesOrigin(proof.flow.sourceValue.origin, occurrence)
            is QueryNamedCallbackReferenceFlowDocument.Unavailable -> true
            is QueryNamedCallbackReferenceFlowDocument.Direct -> {
                val owner = proof.binding.owner as? QueryCallbackBodyDocument.Named ?: return false
                owner.callable == lexicalOwner &&
                    proof.binding.occurrence.contains(occurrence) &&
                    lexicalOwner.declaration.contains(proof.binding.occurrence)
            }
            is QueryNamedCallbackReferenceFlowDocument.Supplied -> proof.admits(occurrence, lexicalOwner)
        }
    }

    private fun matchesOrigin(
        origin: QueryImmutableCallbackValueOriginDocument,
        occurrence: RelationOccurrenceDocument,
    ): Boolean {
        if (origin !is QueryImmutableCallbackValueOriginDocument.Named) return false
        if (!origin.occurrence.sameSite(occurrence) || origin.target != target) return false
        return origin.dispatchReceiver == dispatchReceiver && origin.extensionReceiver == extensionReceiver
    }
}

private fun QueryCallbackReferenceReceiverDocument.admits(occurrence: RelationOccurrenceDocument): Boolean =
    when (this) {
        is QueryCallbackReferenceReceiverDocument.Bound -> occurrence.contains(this.occurrence)
        is QueryCallbackReferenceReceiverDocument.Implicit -> declaration.validDeclaration()
        QueryCallbackReferenceReceiverDocument.Absent,
        QueryCallbackReferenceReceiverDocument.Unbound -> true
    }

private fun QueryNamedCallbackReferenceFlowDocument.Supplied.admits(
    occurrence: RelationOccurrenceDocument,
    lexicalOwner: QueryCallbackCallableDocument,
): Boolean {
    if (
        !binding.validCallableMapping(binding.invocation.callable.basis) ||
            !binding.validInvocationSite() ||
            !binding.parameterIdentity().validParameter()
    )
        return false
    if (
        !binding.invocationOccurrence.contains(occurrence) ||
            !lexicalOwner.declaration.contains(binding.invocationOccurrence)
    )
        return false
    if (
        binding.invocationOwner !is QueryCallbackBodyDocument.Named ||
            !binding.invocationOwner.isReceivingCallable(lexicalOwner)
    )
        return false
    return CallbackInvocationRouteProof(binding.invocation.callable.basis, invocations.values, emptyList(), forwarding)
        .admitParameterInvocations(binding.parameterIdentity(), binding.invocation.callable) is Refinement.Refined
}
