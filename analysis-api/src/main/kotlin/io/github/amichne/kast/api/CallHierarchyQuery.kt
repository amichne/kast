@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class CallHierarchyQuery(
    @DocField(description = "File position identifying the function or method to expand.")
    val position: FilePosition,
    @DocField(description = "INCOMING for callers or OUTGOING for callees.")
    val direction: CallDirection,
    @DocField(description = "Maximum tree depth to traverse.")
    val depth: Int = 3,
    @DocField(description = "Maximum total call nodes to return across the entire tree.")
    val maxTotalCalls: Int = 256,
    @DocField(description = "Maximum direct children per node before truncation.")
    val maxChildrenPerNode: Int = 64,
    @DocField(description = "Optional timeout in milliseconds for the traversal.")
    val timeoutMillis: Long? = null,
)
