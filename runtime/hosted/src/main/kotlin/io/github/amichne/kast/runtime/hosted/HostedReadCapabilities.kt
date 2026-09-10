package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings

/** Only these canonical bindings have project-bound implementations in this package. */
internal object HostedReadCapabilities {
    private val bindings = listOf(
        CanonicalOperationWireBindings.queryRun,
        CanonicalOperationWireBindings.symbolDiscover,
        CanonicalOperationWireBindings.symbolInspect,
        CanonicalOperationWireBindings.sourceRead,
        CanonicalOperationWireBindings.relationRead,
        CanonicalOperationWireBindings.traversalRun,
        CanonicalOperationWireBindings.diagnosticCheck,
    )
    val operations: List<String> = listOf("DESCRIBE", "CLASS_LOOKUP", "DIRECT_SUPERTYPE") + bindings.map { it.operation.name }
    val querySchema: String = CanonicalOperationWireBindings.queryRun.schema.value
}
