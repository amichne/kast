@file:OptIn(io.amichne.konditional.api.KonditionalInternalApi::class)

package io.amichne.konditional.core.registry

import io.amichne.konditional.core.instance.Configuration

/**
 * An immutable, complete snapshot of a namespace's configuration at a point in time.
 *
 * [NamespaceSnapshot] is the unit of atomic exchange in [InMemoryNamespaceRegistry].
 * Readers are guaranteed to observe either a complete previous snapshot or a complete
 * new snapshot — never partial state — because the registry uses a single
 * `AtomicReference<NamespaceSnapshot>` per namespace.
 *
 * ## Immutability
 *
 * [NamespaceSnapshot] is a Kotlin `data class` wrapping an already-immutable [Configuration].
 * Neither the snapshot nor its wrapped configuration is mutated after creation.
 *
 * ## Version
 *
 * The snapshot version is derived from
 * [configuration.metadata.version][io.amichne.konditional.core.instance.ConfigurationMetadataView.version].
 * A `null` version indicates an empty or initial (pre-load) snapshot.
 *
 * @property configuration The immutable flag configuration held by this snapshot.
 */
data class NamespaceSnapshot(
    val configuration: Configuration,
    val allDisabled: Boolean = false,
) {
    /**
     * Version string from the configuration metadata, or `null` for an initial empty snapshot.
     */
    val version: String? get() = configuration.metadata.version

    companion object {
        /**
         * The empty sentinel snapshot used before any configuration has been loaded.
         */
        val empty: NamespaceSnapshot = NamespaceSnapshot(Configuration(emptyMap()))
    }
}
