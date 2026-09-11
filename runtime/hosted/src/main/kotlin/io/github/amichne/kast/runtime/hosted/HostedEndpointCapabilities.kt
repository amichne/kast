package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings

/** Endpoint compatibility includes effects; the admitted read set remains independently closed. */
internal object HostedEndpointCapabilities {
    const val protocol = 3
    val operations: List<String> =
        HostedReadCapabilities.operations +
            listOf(
                CanonicalOperationWireBindings.changePlan.operation.name,
                "CHANGE_APPROVAL_PREPARE",
                CanonicalOperationWireBindings.changeApply.operation.name,
                CanonicalOperationWireBindings.changeRecover.operation.name,
            )
}
