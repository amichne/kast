@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class CallNode(
    @DocField(description = "The function or method at this node in the call tree.")
    val symbol: Symbol,
    @DocField(description = "Source location of the call expression, if available.")
    val callSite: Location? = null,
    @DocField(description = "Present when this node's subtree was truncated.")
    val truncation: CallNodeTruncation? = null,
    @DocField(description = "Child nodes (callers if INCOMING, callees if OUTGOING).")
    val children: List<CallNode>,
)

@Serializable
data class CallNodeTruncation(
    @DocField(description = "Why this node's subtree was truncated.")
    val reason: CallNodeTruncationReason,
    @DocField(description = "Human-readable details about the truncation.")
    val details: String? = null,
)

@Serializable
enum class CallNodeTruncationReason {
    CYCLE,
    MAX_TOTAL_CALLS,
    MAX_CHILDREN_PER_NODE,
    TIMEOUT,
}
