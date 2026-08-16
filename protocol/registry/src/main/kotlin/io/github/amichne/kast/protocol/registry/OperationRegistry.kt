package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.kernel.OperationId
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.CanonicalOperationResolution
import io.github.amichne.kast.protocol.contract.SchemaIdentity

/**
 * Weak registry-admission metadata carrying only a refined permanent identity.
 *
 * Implementations other than [OperationDefinition] remain boundary candidates and cannot enter a
 * successfully constructed [OperationRegistry].
 */
interface OperationMetadata {
    val id: OperationId
}

/** Immutable, exactly complete definitions indexed by canonical operation identity. */
class OperationRegistry private constructor(
    definitionsByOperation: Map<CanonicalOperation, OperationDefinition<*, *, *, *, *>>,
) {
    private val definitionsByOperation = definitionsByOperation.toMap()
    private val definitionsById = this.definitionsByOperation.values.associateBy { it.id }

    val definitions: List<OperationDefinition<*, *, *, *, *>> =
        CanonicalOperation.entries.map(this.definitionsByOperation::getValue)

    /** Resolves a refined operation identity without manufacturing a canonical operation. */
    fun lookup(id: OperationId): OperationLookup = when (CanonicalOperation.resolve(id)) {
        is CanonicalOperationResolution.Unknown -> OperationLookup.Unknown(id)
        is CanonicalOperationResolution.Known ->
            OperationLookup.Found(definitionsById.getValue(id))
    }

    companion object {
        /**
         * Proof transition: `Iterable<OperationMetadata> -> OperationRegistryConstruction`.
         *
         * Establishes exactly one fully typed definition for every canonical operation, unique
         * operation and schema identities, and canonical ordering. [OperationRegistryFailure] is
         * the closed expected failure. Weak metadata iteration is permitted only at runtime
         * composition.
         */
        fun create(metadata: Iterable<OperationMetadata>): OperationRegistryConstruction {
            val materialized = metadata.toList()
            val typed = materialized.filterIsInstance<OperationDefinition<*, *, *, *, *>>()
            val failures = buildSet {
                materialized
                    .groupingBy { it.id }
                    .eachCount()
                    .filterValues { count -> count > 1 }
                    .keys
                    .sorted()
                    .forEach { add(OperationRegistryFailure.DuplicateOperationId(it)) }

                typed
                    .groupBy { it.schema }
                    .filterValues { definitions ->
                        definitions.mapTo(mutableSetOf()) { it.operation }.size > 1
                    }
                    .keys
                    .sorted()
                    .forEach { add(OperationRegistryFailure.DuplicateSchemaIdentity(it)) }

                materialized.forEach { candidate ->
                    when (CanonicalOperation.resolve(candidate.id)) {
                        is CanonicalOperationResolution.Unknown ->
                            add(OperationRegistryFailure.UnknownOperationId(candidate.id))
                        is CanonicalOperationResolution.Known ->
                            if (candidate !is OperationDefinition<*, *, *, *, *>) {
                                add(OperationRegistryFailure.UntypedOperationMetadata(candidate.id))
                            }
                    }
                }

                val typedOperations = typed.mapTo(mutableSetOf()) { it.operation }
                CanonicalOperation.entries
                    .filterNot(typedOperations::contains)
                    .forEach { add(OperationRegistryFailure.MissingOperationId(it.id)) }
            }

            return if (failures.isEmpty()) {
                OperationRegistryConstruction.Created(
                    OperationRegistry(typed.associateBy { it.operation }),
                )
            } else {
                OperationRegistryConstruction.Rejected(failures)
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

    data class DuplicateSchemaIdentity(
        val schema: SchemaIdentity,
    ) : OperationRegistryFailure

    data class MissingOperationId(
        val id: OperationId,
    ) : OperationRegistryFailure

    data class UnknownOperationId(
        val id: OperationId,
    ) : OperationRegistryFailure

    data class UntypedOperationMetadata(
        val id: OperationId,
    ) : OperationRegistryFailure
}

sealed interface OperationLookup {
    data class Found(
        val definition: OperationDefinition<*, *, *, *, *>,
    ) : OperationLookup

    data class Unknown(
        val id: OperationId,
    ) : OperationLookup
}
