package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class CallHierarchyQuery(
    val position: FilePosition,
    val direction: CallDirection,
    val depth: Int = 3,
    val maxTotalCalls: Int = 200,
    val maxChildrenPerNode: Int = 50,
    val timeoutMillis: Long? = null,
    val includeExternalSymbols: Boolean = false,
)
