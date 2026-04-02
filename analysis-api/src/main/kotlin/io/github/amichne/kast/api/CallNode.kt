package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class CallNode(
    val symbol: Symbol,
    val callSite: Location? = null,
    val expansion: CallNodeExpansion = CallNodeExpansion.EXPANDED,
    val children: List<CallNode>,
)

@Serializable
enum class CallNodeExpansion {
    EXPANDED,
    MAX_DEPTH,
    CYCLE_TRUNCATED,
    MAX_TOTAL_CALLS_TRUNCATED,
    MAX_CHILDREN_TRUNCATED,
    TIMEOUT_TRUNCATED,
}
