package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationProviderItemDescriptor

/** One live provider value paired with its detached canonical ordering identity. */
internal data class IntellijCanonicalRelationProviderItem<out Value>(
    val descriptor: RelationProviderItemDescriptor,
    val value: Value,
)

/** Establishes provider order before any result, work, byte, or continuation boundary is applied. */
internal fun <Value> Iterable<Value>.canonicalRelationProviderOrder(
    descriptorOf: (Value) -> RelationProviderItemDescriptor
): List<IntellijCanonicalRelationProviderItem<Value>> = map { value ->
    IntellijCanonicalRelationProviderItem(descriptorOf(value), value)
}
    .sortedBy { item -> item.descriptor.value }
