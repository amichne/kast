package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class CallNode(
    val symbol: Symbol,
    val children: List<CallNode>,
)
