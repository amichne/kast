package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.Collections

/** Exhaustion of every in-domain call/reference to one formal's callable, including explicit empty supply. */
class CompleteCallbackSupplierPartition
private constructor(
    val formal: CallbackParameterIdentity,
    val suppliers: List<CallbackParameterSupplier>,
    val incoming: List<CallbackParameterForwarding>,
) {
    companion object {
        fun fromCompiler(
            formal: CallbackParameterIdentity,
            suppliers: List<CallbackParameterSupplier>,
            incoming: List<CallbackParameterForwarding>,
            scan: CallbackInvocationScan,
        ): Refinement<CompleteCallbackSupplierPartition, CallbackSupplierFailure> {
            if (scan != CallbackInvocationScan.EXHAUSTIVE)
                return Refinement.Rejected(CallbackSupplierFailure.INCOMPLETE_SCAN)
            if (suppliers.distinct().size != suppliers.size || incoming.distinct().size != incoming.size)
                return Refinement.Rejected(CallbackSupplierFailure.DUPLICATE_INPUT)
            if (
                suppliers.any { it.binding.invocationOwner !is RelationCallableBody.Named } ||
                    incoming.any { it.target.invocationOwner !is RelationCallableBody.Named }
            )
                return Refinement.Rejected(CallbackSupplierFailure.UNPROVEN_OWNER)
            if (suppliers.any { it.formal != formal })
                return Refinement.Rejected(CallbackSupplierFailure.FORMAL_MISMATCH)
            for (edge in incoming) {
                when (val target = edge.target.formalIdentity()) {
                    is Refinement.Refined ->
                        if (target.value != formal) return Refinement.Rejected(CallbackSupplierFailure.FORMAL_MISMATCH)
                    is Refinement.Rejected -> return Refinement.Rejected(CallbackSupplierFailure.FORMAL_MISMATCH)
                }
            }
            return Refinement.Refined(
                CompleteCallbackSupplierPartition(
                    formal,
                    Collections.unmodifiableList(suppliers.toList()),
                    Collections.unmodifiableList(incoming.toList()),
                )
            )
        }
    }
}

/** Finite reverse forwarding closure. Every reachable formal has its own exhausted supplier partition. */
class CompleteCallbackSupplierInventory
private constructor(
    val root: CallbackParameterIdentity,
    val domain: RelationScopeFingerprint,
    val partitions: List<CompleteCallbackSupplierPartition>,
) {
    val retainedBytes: Long =
        partitions.fold(root.retainedBytes.addBytes(4096L)) { bytes, partition ->
            partition.suppliers.fold(
                partition.incoming.fold(bytes.addBytes(partition.formal.retainedBytes)) { total, edge ->
                    total.addBytes(edge.retainedBytes)
                }
            ) { total, supplier ->
                total.addBytes(supplier.retainedBytes)
            }
        }

    companion object {
        fun fromCompiler(
            root: CallbackParameterIdentity,
            domain: RelationScopeFingerprint,
            partitions: List<CompleteCallbackSupplierPartition>,
        ): Refinement<CompleteCallbackSupplierInventory, CallbackSupplierFailure> {
            val indexed = partitions.associateBy { it.formal }
            if (indexed.size != partitions.size) return Refinement.Rejected(CallbackSupplierFailure.DUPLICATE_INPUT)
            if (partitions.any { it.formal.callable.lease.identity != root.callable.lease.identity })
                return Refinement.Rejected(CallbackSupplierFailure.BASIS_MISMATCH)
            if (
                partitions.any {
                    it.formal.callable.scope != root.callable.scope ||
                        it.formal.callable.constraints != root.callable.constraints
                }
            )
                return Refinement.Rejected(CallbackSupplierFailure.DOMAIN_MISMATCH)
            val reached = linkedSetOf<CallbackParameterIdentity>()
            val pending = ArrayDeque<CallbackParameterIdentity>().apply { add(root) }
            while (pending.isNotEmpty()) {
                val formal = pending.removeFirst()
                if (!reached.add(formal)) continue
                val partition = indexed[formal] ?: return Refinement.Rejected(CallbackSupplierFailure.MISSING_PARTITION)
                partition.incoming.forEach { pending.add(it.source) }
            }
            if (reached != indexed.keys) return Refinement.Rejected(CallbackSupplierFailure.DISCONNECTED_PARTITION)
            return Refinement.Refined(
                CompleteCallbackSupplierInventory(root, domain, Collections.unmodifiableList(partitions.toList()))
            )
        }
    }
}

sealed interface CallbackSupplierInventoryEvidence {
    data class Exhaustive(val inventory: CompleteCallbackSupplierInventory) : CallbackSupplierInventoryEvidence

    data class Unavailable(val cause: CallbackInvocationFlowCause) : CallbackSupplierInventoryEvidence
}
