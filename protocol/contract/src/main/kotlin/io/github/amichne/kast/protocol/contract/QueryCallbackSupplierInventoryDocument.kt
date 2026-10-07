package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

/** Each entry has independently exhausted the selected source domain for this exact formal. */
data class QueryCallbackSupplierPartitionDocument(
    val formal: QueryCallbackParameterIdentityDocument,
    val suppliers: BoundedProtocolList<QueryCallbackParameterSupplierDocument>,
    val incoming: BoundedProtocolList<QueryCallbackForwardingDocument>,
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
            !it.binding.parameterIdentity().sameParameter(formal) ||
                it.binding.invocation.callable.basis != basis ||
                it.binding.invocationOwner !is QueryCallbackBodyDocument.Named
        }
    )
        return false
    return incoming.values.all {
        it.target.invocationOwner is QueryCallbackBodyDocument.Named &&
            it.target.parameterIdentity().sameParameter(formal) &&
            it.admitSupplierForwarding(basis) is Refinement.Refined
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
