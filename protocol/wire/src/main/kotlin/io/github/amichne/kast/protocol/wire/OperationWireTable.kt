package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.SchemaIdentity

/** Internal generated serializer table; public operation authority remains in OperationRegistry. */
internal class OperationWireTable private constructor(
    bindingsByOperation: Map<CanonicalOperation, OperationWireBinding<*, *, *, *>>,
) {
    val bindings: List<OperationWireBinding<*, *, *, *>> =
        CanonicalOperation.entries.map(bindingsByOperation::getValue)

    companion object {
        /**
         * Proof transition: `Iterable<OperationWireBinding<*, *, *, *>> ->
         * OperationWireTableConstruction`.
         *
         * Establishes exactly one generated serializer binding per canonical operation and unique
         * schema identity. [OperationWireTableFailure] is the closed expected failure. The raw
         * iterable is permitted only at runtime composition.
         */
        fun create(
            bindings: Iterable<OperationWireBinding<*, *, *, *>>,
        ): OperationWireTableConstruction {
            val materialized = bindings.toList()
            val failures = buildSet {
                materialized
                    .groupingBy { it.operation }
                    .eachCount()
                    .filterValues { count -> count > 1 }
                    .keys
                    .sortedBy { it.ordinal }
                    .forEach { add(OperationWireTableFailure.DuplicateSerializerBinding(it)) }

                materialized
                    .groupBy { it.schema }
                    .filterValues { schemaBindings ->
                        schemaBindings.mapTo(mutableSetOf()) { it.operation }.size > 1
                    }
                    .keys
                    .sorted()
                    .forEach { add(OperationWireTableFailure.DuplicateSchemaBinding(it)) }

                val present = materialized.mapTo(mutableSetOf()) { it.operation }
                CanonicalOperation.entries
                    .filterNot(present::contains)
                    .forEach { add(OperationWireTableFailure.MissingSerializerBinding(it)) }
            }
            return if (failures.isEmpty()) {
                OperationWireTableConstruction.Created(
                    OperationWireTable(materialized.associateBy { it.operation }),
                )
            } else {
                OperationWireTableConstruction.Rejected(failures)
            }
        }
    }
}

internal sealed interface OperationWireTableConstruction {
    data class Created(
        val table: OperationWireTable,
    ) : OperationWireTableConstruction

    data class Rejected(
        val failures: Set<OperationWireTableFailure>,
    ) : OperationWireTableConstruction
}

internal sealed interface OperationWireTableFailure {
    data class DuplicateSerializerBinding(
        val operation: CanonicalOperation,
    ) : OperationWireTableFailure

    data class DuplicateSchemaBinding(
        val schema: SchemaIdentity,
    ) : OperationWireTableFailure

    data class MissingSerializerBinding(
        val operation: CanonicalOperation,
    ) : OperationWireTableFailure
}
