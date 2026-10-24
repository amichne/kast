package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

/** Each entry has independently exhausted the selected source domain for this exact formal. */
data class QueryCallbackSupplierPartitionDocument(
    val formal: QueryCallbackParameterIdentityDocument,
    val suppliers: BoundedProtocolList<QueryCallbackParameterSupplierDocument>,
    val incoming: BoundedProtocolList<QueryCallbackForwardingDocument>,
    val ownerBindings: BoundedProtocolList<QueryCallbackBodyBindingDocument>,
)

sealed interface QueryCallbackSupplierInventoryDocument {
    data class Unavailable(val cause: QueryCallbackFlowCauseDocument) : QueryCallbackSupplierInventoryDocument

    class Exhaustive
    private constructor(
        val root: QueryCallbackParameterIdentityDocument,
        val basis: ImpactSemanticBasisDocument,
        val domain: QueryRelationDomainFingerprint,
        val partitions: BoundedProtocolList<QueryCallbackSupplierPartitionDocument>,
    ) : QueryCallbackSupplierInventoryDocument {
        companion object {
            fun create(
                root: QueryCallbackParameterIdentityDocument,
                basis: ImpactSemanticBasisDocument,
                domain: QueryRelationDomainFingerprint,
                partitions: BoundedProtocolList<QueryCallbackSupplierPartitionDocument>,
            ): Refinement<Exhaustive, QueryCallbackSupplierFailure> {
                if (!root.validParameter()) return invalid()
                val indexed = partitions.values.associateBy { it.formal.formalSite() }
                if (indexed.size != partitions.values.size) return invalid()
                if (partitions.values.any { !it.admitsPartition(basis) }) return invalid()
                if (!indexed.exhausts(root)) return invalid()
                return Refinement.Refined(Exhaustive(root, basis, domain, partitions))
            }

            private fun invalid() = Refinement.Rejected(QueryCallbackSupplierFailure.INVALID_INVENTORY)
        }
    }
}

private fun QueryCallbackSupplierPartitionDocument.admitsPartition(basis: ImpactSemanticBasisDocument): Boolean {
    if (!formal.validParameter()) return false
    if (
        suppliers.values.distinct().size != suppliers.values.size ||
            incoming.values.distinct().size != incoming.values.size
    )
        return false
    if (
        suppliers.values.any {
            !it.binding.parameterIdentity().sameParameter(formal) || it.binding.invocation.callable.basis != basis
        }
    )
        return false
    if (!admitsOwners(basis)) return false
    return incoming.values.all {
        it.target.parameterIdentity().sameParameter(formal) &&
            it.admitSupplierForwarding(basis, ownerBindings.values) is Refinement.Refined
    }
}

private fun QueryCallbackSupplierPartitionDocument.admitsOwners(basis: ImpactSemanticBasisDocument): Boolean {
    val bindings = suppliers.values.map { it.binding } + incoming.values.map { it.target }
    val owners = ownerBindings.values
    if (owners.admitExecutionInventory(emptySet()) is Refinement.Rejected) return false
    if (owners.admitOwnerBindings(bindings.map { it.invocationOwner }, emptyList(), basis, 0) is Refinement.Rejected)
        return false
    return suppliers.values.all { supplier ->
        val root = owners.supplierRoot(supplier.binding.invocationOwner) ?: return false
        when (val selection = supplier.selection) {
            is QueryCallbackSupplierSelectionDocument.Explicit ->
                root.reference(basis) == supplier.value.destination.enclosing
            is QueryCallbackSupplierSelectionDocument.Default -> root.reference(basis) == selection.invocationEnclosing
        }
    } &&
        incoming.values.all { edge ->
            val root = owners.supplierRoot(edge.target.invocationOwner) ?: return false
            root.compilerTarget == edge.source.callable.compilerTarget &&
                root.declaration.sameSite(edge.source.callable.declaration)
        }
}

private fun List<QueryCallbackBodyBindingDocument>.supplierRoot(
    owner: QueryCallbackBodyDocument
): QueryCallbackCallableDocument? =
    when (owner) {
        is QueryCallbackBodyDocument.Named -> owner.callable
        is QueryCallbackBodyDocument.Anonymous ->
            when (val root = admittedOwnerRoot(owner)) {
                is Refinement.Refined -> root.value
                is Refinement.Rejected -> null
            }
    }

private fun Map<CallbackFormalSite, QueryCallbackSupplierPartitionDocument>.exhausts(
    root: QueryCallbackParameterIdentityDocument
): Boolean {
    val reached = linkedSetOf<CallbackFormalSite>()
    val pending = ArrayDeque<CallbackFormalSite>().apply { add(root.formalSite()) }
    while (pending.isNotEmpty()) {
        val formal = pending.removeFirst()
        if (!reached.add(formal)) continue
        val partition = this[formal] ?: return false
        partition.incoming.values.forEach { pending.add(it.source.formalSite()) }
    }
    return reached == keys
}
