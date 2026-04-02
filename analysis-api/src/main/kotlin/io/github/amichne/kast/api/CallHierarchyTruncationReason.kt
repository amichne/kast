package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
enum class CallHierarchyTruncationReason {
    DEPTH_LIMIT,
    MAX_TOTAL_CALLS,
    MAX_CHILDREN_PER_NODE,
    TIMEOUT,
    CYCLE,
}
