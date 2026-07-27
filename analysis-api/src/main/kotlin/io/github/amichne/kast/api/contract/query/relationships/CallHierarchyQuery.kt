@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.query

import io.github.amichne.kast.api.contract.CallDirection
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.ExperimentalSerializationApi

import kotlinx.serialization.Serializable

@Serializable
data class CallHierarchyQuery(
    @DocField(description = "File position identifying the function or method to expand.")
    val position: FilePosition,
    @DocField(description = "INCOMING for callers or OUTGOING for callees.")
    val direction: CallDirection,
    @DocField(description = "Maximum tree depth to traverse.", defaultValue = "3")
    val depth: Int = 3,
    @DocField(description = "Maximum total call nodes to return across the entire tree.", defaultValue = "256")
    val maxTotalCalls: Int = 256,
    @DocField(description = "Maximum direct children per node before truncation.", defaultValue = "64")
    val maxChildrenPerNode: Int = 64,
    @DocField(description = "Optional timeout in milliseconds for the traversal.")
    val timeoutMillis: Long? = null,
)
