package io.github.amichne.kast.relation.contract

import java.util.IdentityHashMap

/** Request-local accounting of immutable inventory owners; equal detached inventories remain distinct owners. */
class RelationProviderRetainedGraph(private val parent: RelationProviderRetainedGraph? = null) {
    private val inventories = IdentityHashMap<List<RelationProviderLocator>, RelationInventoryRetentionCharge>()
    private var nextOwnerOrdinal: Long = parent?.nextOwnerOrdinal ?: 1L

    /** Retains metadata only for identities already needed by accounting; never retains source payloads or event history. */
    fun retentionLedger(): RelationProviderRetentionLedger =
        RelationProviderRetentionLedger(allInventories().values.sortedBy { it.owner.value })

    fun commitToParent() {
        parent?.let { owner ->
            owner.inventories.putAll(inventories)
            owner.nextOwnerOrdinal = nextOwnerOrdinal
        }
    }

    internal fun inventory(values: List<RelationProviderLocator>, bytes: Long): Long {
        val existing = find(values)
        if (existing != null) {
            inventories[values] = existing.referenced()
            return REFERENCE_BYTES
        }
        val ordinal = nextOwnerOrdinal++
        inventories[values] = RelationInventoryRetentionCharge(
            RelationRetainedInventoryOwnerId.issue(ordinal),
            bytes.ledgerBytes(),
            RelationRetainedReferenceCount.First,
        )
        return bytes.addStorageBytes(OWNER_LEDGER_BYTES).addStorageBytes(REFERENCE_BYTES)
    }

    private fun find(values: List<RelationProviderLocator>): RelationInventoryRetentionCharge? =
        inventories[values] ?: parent?.find(values)

    private fun allInventories(): IdentityHashMap<List<RelationProviderLocator>, RelationInventoryRetentionCharge> =
        IdentityHashMap<List<RelationProviderLocator>, RelationInventoryRetentionCharge>().apply {
            parent?.allInventories()?.let(::putAll)
            putAll(inventories)
        }
}

/** Per-state structure, advancing cursor and ledger references still need an independent conservative allowance. */
internal const val PROVIDER_STATE_STORAGE_BYTES = 512L

internal fun Long.addStorageBytes(other: Long): Long =
    if (this < 0L || other < 0L || this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other
