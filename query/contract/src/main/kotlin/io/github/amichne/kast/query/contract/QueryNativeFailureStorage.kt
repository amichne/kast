package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.ValueFlowRejection

internal fun ValueFlowRejection.storageBytes(graph: QueryImpactRetainedGraph): Long =
    graph.node(this) {
        when (this) {
            is ValueFlowRejection.Native.Unexpected ->
                graph.node(failure) {
                    graph.text(failure.exceptionType).saturatedAdd(graph.collection(failure.adapterFrames, graph::text))
                }
            is ValueFlowRejection.Native.HostUnavailable,
            is ValueFlowRejection.Native.RelationRejected,
            is ValueFlowRejection.Native.PortUnconfigured,
            ValueFlowRejection.STALE_SITE,
            ValueFlowRejection.OUTSIDE_DOMAIN,
            ValueFlowRejection.OWNER_UNAVAILABLE,
            ValueFlowRejection.AUTHORITY_MOVED,
            ValueFlowRejection.UNSUPPORTED_SEED,
            ValueFlowRejection.UNRESOLVED_SEED,
            ValueFlowRejection.GRANT_TOO_SMALL,
            ValueFlowRejection.NESTED_EXECUTION -> 0L
        }
    }
