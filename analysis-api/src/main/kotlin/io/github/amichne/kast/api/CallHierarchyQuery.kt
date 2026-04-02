package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class CallHierarchyQuery(
    val position: FilePosition,
    val direction: CallDirection,
    val depth: Int = 3,
    val maxTotalCalls: Int = 500,
    val maxChildrenPerNode: Int = 100,
    val timeoutMillis: Long = 2_000,
)
