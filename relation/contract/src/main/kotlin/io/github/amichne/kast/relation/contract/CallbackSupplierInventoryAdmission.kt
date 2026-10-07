package io.github.amichne.kast.relation.contract

internal fun CallbackSupplierInventoryEvidence.admits(
    formal: CallbackParameterIdentity,
    domain: RelationScopeFingerprint,
): Boolean =
    when (this) {
        is CallbackSupplierInventoryEvidence.Unavailable -> true
        is CallbackSupplierInventoryEvidence.Exhaustive -> inventory.root == formal && inventory.domain == domain
    }

internal fun CallbackSupplierInventoryEvidence.canonicalProjection(): String =
    when (this) {
        is CallbackSupplierInventoryEvidence.Unavailable -> "UNAVAILABLE:${cause.name}"
        is CallbackSupplierInventoryEvidence.Exhaustive ->
            buildString {
                fun field(value: String) {
                    append(value.length).append(':').append(value)
                }
                field("EXHAUSTIVE")
                field(inventory.root.canonicalProjection())
                field(inventory.domain.value)
                field(inventory.partitions.size.toString())
                for (partition in inventory.partitions) {
                    field(partition.formal.canonicalProjection())
                    field(partition.incoming.size.toString())
                    partition.incoming.forEach { field(it.canonicalProjection()) }
                    field(partition.suppliers.size.toString())
                    partition.suppliers.forEach { field(it.canonicalProjection()) }
                }
            }
    }
