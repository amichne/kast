package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.kernel.OperationId

/**
 * Immutable definitions indexed by their unique permanent operation identities.
 */
class OperationRegistry private constructor(
    definitionsById: Map<OperationId, OperationDefinition<*, *, *, *>>,
) {
    private val definitionsById: Map<OperationId, OperationDefinition<*, *, *, *>> =
        definitionsById.toMap()

    val definitions: List<OperationDefinition<*, *, *, *>> =
        this.definitionsById.values.sortedBy { it.id }

    fun lookup(id: OperationId): OperationLookup =
        definitionsById[id]
            ?.let(OperationLookup::Found)
        ?: OperationLookup.Missing(id)

    companion object {
        /**
         * Proof transition: `Iterable<OperationDefinition<*, *, *, *>> ->
         * OperationRegistryConstruction`.
         *
         * Establishes one immutable lookup entry per permanent operation ID and deterministic
         * definition ordering. [OperationRegistryFailure] is the closed expected failure. Raw
         * definition iteration is permitted only at the runtime composition boundary.
         */
        fun create(
            definitions: Iterable<OperationDefinition<*, *, *, *>>,
        ): OperationRegistryConstruction {
            val materialized = definitions.toList()
            val duplicateFailures = materialized
                .groupingBy { it.id }
                .eachCount()
                .filterValues { count -> count > 1 }
                .keys
                .sorted()
                .map(OperationRegistryFailure::DuplicateOperationId)
                .toSet()

            return if (duplicateFailures.isEmpty()) {
                OperationRegistryConstruction.Created(
                    OperationRegistry(materialized.associateBy { it.id }),
                )
            } else {
                OperationRegistryConstruction.Rejected(duplicateFailures)
            }
        }
    }
}

sealed interface OperationRegistryConstruction {
    data class Created(
        val registry: OperationRegistry,
    ) : OperationRegistryConstruction

    data class Rejected(
        val failures: Set<OperationRegistryFailure>,
    ) : OperationRegistryConstruction
}

sealed interface OperationRegistryFailure {
    data class DuplicateOperationId(
        val id: OperationId,
    ) : OperationRegistryFailure
}

sealed interface OperationLookup {
    data class Found(
        val definition: OperationDefinition<*, *, *, *>,
    ) : OperationLookup

    data class Missing(
        val id: OperationId,
    ) : OperationLookup
}
