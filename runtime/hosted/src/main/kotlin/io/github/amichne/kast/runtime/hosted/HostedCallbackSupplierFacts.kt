package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.CallbackReadmission
import io.github.amichne.kast.relation.contract.CallbackSupplierCacheLookup
import io.github.amichne.kast.relation.contract.CallbackSupplierCachePort
import io.github.amichne.kast.relation.contract.CompleteCallbackSupplierInventory
import io.github.amichne.kast.relation.contract.RelationScopeFingerprint
import io.github.amichne.kast.topology.build.SemanticCallbackFactStore
import io.github.amichne.kast.topology.build.SemanticCallbackPublication
import io.github.amichne.kast.topology.build.SemanticCallbackSupplierLookup
import io.github.amichne.kast.topology.contract.SemanticDependencySnapshot
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation

/** Full workspace supplier universe, including absence; never narrowed to the formal's dependency closure. */
internal class HostedCallbackSupplierFacts(
    private val snapshot: SemanticDependencySnapshot,
    private val store: SemanticCallbackFactStore,
    private val observation: IntellijReadObservation,
) : CallbackSupplierCachePort {
    override fun find(
        root: CallbackParameterIdentity,
        domain: RelationScopeFingerprint,
        readmit: (CompleteCallbackSupplierInventory) -> CallbackReadmission<CompleteCallbackSupplierInventory>,
    ): CallbackSupplierCacheLookup =
        when (val cached = store.findSuppliers(snapshot, root, domain)) {
            SemanticCallbackSupplierLookup.Missing -> CallbackSupplierCacheLookup.Miss
            is SemanticCallbackSupplierLookup.Current -> CallbackSupplierCacheLookup.Found(cached.inventory)
            is SemanticCallbackSupplierLookup.Reusable -> restore(root, domain, readmit(cached.previous))
            is SemanticCallbackSupplierLookup.Invalidated -> {
                observation.count(IntellijReadCounter.SEMANTIC_FACT_PARTITIONS_INVALIDATED)
                observation.count(IntellijReadCounter.SEMANTIC_FACT_SUPPLIER_INVENTORIES_INVALIDATED)
                observation.count(IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REJECTIONS)
                CallbackSupplierCacheLookup.Miss
            }
            is SemanticCallbackSupplierLookup.Rejected -> {
                observation.count(IntellijReadCounter.SEMANTIC_FACT_GENERATIONS_REJECTED)
                CallbackSupplierCacheLookup.Miss
            }
        }

    private fun restore(
        root: CallbackParameterIdentity,
        domain: RelationScopeFingerprint,
        restored: CallbackReadmission<CompleteCallbackSupplierInventory>,
    ): CallbackSupplierCacheLookup {
        val inventory =
            when (restored) {
                is Refinement.Refined -> restored.value
                is Refinement.Rejected -> {
                    observation.count(IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REJECTIONS)
                    return CallbackSupplierCacheLookup.Miss
                }
            }
        if (inventory.root != root || inventory.domain != domain) {
            observation.count(IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REJECTIONS)
            return CallbackSupplierCacheLookup.Miss
        }
        return when (publish(inventory)) {
            SemanticCallbackPublication.Published,
            SemanticCallbackPublication.CapacityExceeded -> CallbackSupplierCacheLookup.Found(inventory)
            is SemanticCallbackPublication.Rejected -> CallbackSupplierCacheLookup.Miss
        }
    }

    override fun retain(inventory: CompleteCallbackSupplierInventory) {
        observation.count(IntellijReadCounter.SEMANTIC_FACT_PARTITIONS_EXTRACTED)
        observation.count(IntellijReadCounter.SEMANTIC_FACT_SUPPLIER_INVENTORIES_EXTRACTED)
        publish(inventory)
    }

    override fun admitted(inventory: CompleteCallbackSupplierInventory) {
        observation.count(IntellijReadCounter.SEMANTIC_FACT_PARTITIONS_REUSED)
        observation.count(IntellijReadCounter.SEMANTIC_FACT_SUPPLIER_INVENTORIES_REUSED)
    }

    private fun publish(inventory: CompleteCallbackSupplierInventory): SemanticCallbackPublication {
        val published = store.publishSuppliers(snapshot, inventory)
        observation.count(
            when (published) {
                SemanticCallbackPublication.Published -> IntellijReadCounter.SEMANTIC_FACT_GENERATIONS_PUBLISHED
                SemanticCallbackPublication.CapacityExceeded,
                is SemanticCallbackPublication.Rejected -> IntellijReadCounter.SEMANTIC_FACT_GENERATIONS_REJECTED
            }
        )
        return published
    }
}
