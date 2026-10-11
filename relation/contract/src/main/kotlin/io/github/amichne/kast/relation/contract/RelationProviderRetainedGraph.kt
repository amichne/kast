package io.github.amichne.kast.relation.contract

import java.util.IdentityHashMap

/** Request-local accounting of immutable inventory owners; equal detached inventories remain distinct owners. */
class RelationProviderRetainedGraph(private val parent: RelationProviderRetainedGraph? = null) {
    private val inventories = IdentityHashMap<List<RelationProviderLocator>, Unit>()

    fun commitToParent() {
        parent?.inventories?.putAll(inventories)
    }

    internal fun inventory(values: List<RelationProviderLocator>, bytes: Long): Long {
        if (contains(values)) return REFERENCE_BYTES
        inventories[values] = Unit
        return bytes.addStorageBytes(OWNER_LEDGER_BYTES).addStorageBytes(REFERENCE_BYTES)
    }

    private fun contains(values: List<RelationProviderLocator>): Boolean =
        inventories.containsKey(values) || parent?.contains(values) == true
}

/** Per-state structure, advancing cursor and ledger references still need an independent conservative allowance. */
internal const val PROVIDER_STATE_STORAGE_BYTES = 512L
private const val OWNER_LEDGER_BYTES = 512L
private const val REFERENCE_BYTES = 8L

internal fun Long.addStorageBytes(other: Long): Long =
    if (this < 0L || other < 0L || this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other
