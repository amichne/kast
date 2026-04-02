package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class CallNode(
    val symbol: Symbol,
    val callSite: Location? = null,
    val truncation: CallNodeTruncation? = null,
    val children: List<CallNode>,
)

@Serializable
data class CallNodeTruncation(
    val reason: CallNodeTruncationReason,
    val details: String? = null,
)

@Serializable
enum class CallNodeTruncationReason {
    CYCLE,
    MAX_TOTAL_CALLS,
    MAX_CHILDREN_PER_NODE,
    TIMEOUT,
}
