package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

internal fun QueryImmutableCallbackFlowDocument.admitOwnerBindings(): Refinement<Unit, QueryCallbackDocumentFailure> {
    when (val inventory = admitExecutionInventory()) {
        is Refinement.Rejected -> return inventory
        is Refinement.Refined -> Unit
    }
    val owners =
        uses.values.mapNotNull { use ->
            when (use) {
                is QueryImmutableCallbackUseDocument.Unused -> null
                is QueryImmutableCallbackUseDocument.Direct -> use.binding.owner
                is QueryImmutableCallbackUseDocument.Supplied -> use.supplier.binding.invocationOwner
            }
        }
    return ownerBindings.values.admitOwnerBindings(owners, obligations.values, sourceValue.source.enclosing.basis, 0)
}

internal fun List<QueryCallbackBodyBindingDocument>.admitsImmutableOwner(
    owner: QueryCallbackBodyDocument,
    enclosing: ImpactDeclarationReferenceDocument,
    obligations: List<QueryCallbackFlowCauseDocument>,
): Boolean =
    when (owner) {
        is QueryCallbackBodyDocument.Named -> owner.callable.reference(enclosing.basis) == enclosing
        is QueryCallbackBodyDocument.Anonymous -> {
            val occurrence = owner.occurrence
            if (!owner.validBody()) false
            else if (
                occurrence.file != enclosing.file ||
                    occurrence.range.startInclusive.value < enclosing.range.start.value ||
                    occurrence.range.endExclusive.value > enclosing.range.end.value
            )
                false
            else if (QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION in obligations) true
            else
                when (val root = admittedOwnerRoot(owner)) {
                    is Refinement.Rejected -> false
                    is Refinement.Refined -> root.value.reference(enclosing.basis) == enclosing
                }
        }
    }

internal fun QueryCallbackParameterSupplierDocument.admitsImmutableOwner(
    ownerBindings: List<QueryCallbackBodyBindingDocument>,
    obligations: List<QueryCallbackFlowCauseDocument>,
): Boolean =
    when (val selection = selection) {
        is QueryCallbackSupplierSelectionDocument.Explicit ->
            ownerBindings.admitsImmutableOwner(binding.invocationOwner, selection.argument.enclosing, obligations)
        is QueryCallbackSupplierSelectionDocument.Default -> admitsDefaultOwner(ownerBindings, obligations)
    }

internal fun QueryImmutableCallbackUseDocument.Supplied.admitSummaryOwners(
    basis: ImpactSemanticBasisDocument
): Refinement<Unit, QueryCallbackDocumentFailure> {
    if (summaryObligations.values != summaryObligations.values.distinct().sortedBy { it.ordinal })
        return Refinement.Rejected(QueryCallbackDocumentFailure.MISSING_OBLIGATION)
    val owners =
        invocations.values.map { it.owner } +
            invocations.values.flatMap { it.forwardings.values.map { edge -> edge.target.invocationOwner } } +
            when (val forwarding = forwarding) {
                QueryCallbackForwardingEvidenceDocument.InvocationRoutes -> emptyList()
                is QueryCallbackForwardingEvidenceDocument.ExhaustedGraph ->
                    forwarding.forwardings.values.map { it.target.invocationOwner }
            }
    return summaryOwnerBindings.values.admitOwnerBindings(owners, summaryObligations.values, basis, 0)
}

internal fun QueryImmutableCallbackFlowDocument.executionOwnerBindings(): List<QueryCallbackBodyBindingDocument> =
    ownerBindings.values +
        uses.values.filterIsInstance<QueryImmutableCallbackUseDocument.Supplied>().flatMap {
            it.summaryOwnerBindings.values
        }

private fun QueryCallbackParameterSupplierDocument.admitsDefaultOwner(
    ownerBindings: List<QueryCallbackBodyBindingDocument>,
    obligations: List<QueryCallbackFlowCauseDocument>,
): Boolean =
    when (val owner = binding.invocationOwner) {
        is QueryCallbackBodyDocument.Named -> true
        is QueryCallbackBodyDocument.Anonymous ->
            if (QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION in obligations) true
            else
                when (val root = ownerBindings.admittedOwnerRoot(owner)) {
                    is Refinement.Rejected -> false
                    is Refinement.Refined -> root.value.declaration.contains(binding.invocationOccurrence)
                }
    }
