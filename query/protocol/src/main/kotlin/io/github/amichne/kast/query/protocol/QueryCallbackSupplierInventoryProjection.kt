package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryCallbackSupplierInventoryDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackSupplierPartitionDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint
import io.github.amichne.kast.relation.contract.CallbackSupplierInventoryEvidence

internal fun CallbackSupplierInventoryEvidence.projectSupplierInventory(
    projection: CallbackProjection
): QueryCallbackSupplierInventoryDocument? =
    when (this) {
        is CallbackSupplierInventoryEvidence.Unavailable ->
            QueryCallbackSupplierInventoryDocument.Unavailable(cause.protocolCallbackDocument())
        is CallbackSupplierInventoryEvidence.Exhaustive -> inventory.projectSupplierInventory(projection)
    }

private fun io.github.amichne.kast.relation.contract.CompleteCallbackSupplierInventory.projectSupplierInventory(
    projection: CallbackProjection
): QueryCallbackSupplierInventoryDocument? {
    val root = projection.parameter(root) ?: return null
    val basis =
        when (val admitted = this.root.callable.lease.identity.impactDocument()) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return null
        }
    val domain =
        when (val admitted = QueryRelationDomainFingerprint.parse(domain.value)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return null
        }
    val partitions = partitions.map { partition ->
        val suppliers =
            BoundedProtocolList.create(
                    partition.suppliers.map { it.projectCallbackSupplier(projection) ?: return null }
                )
                .supplierValue() ?: return null
        val incoming =
            BoundedProtocolList.create(partition.incoming.map { projection.forwarding(it) ?: return null })
                .supplierValue() ?: return null
        QueryCallbackSupplierPartitionDocument(
            projection.parameter(partition.formal) ?: return null,
            suppliers,
            incoming,
        )
    }
    return QueryCallbackSupplierInventoryDocument.Exhaustive.create(
            root,
            basis,
            domain,
            BoundedProtocolList.create(partitions).supplierValue() ?: return null,
        )
        .supplierValue()
}

private fun <V, F> Refinement<V, F>.supplierValue(): V? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }
