package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
enum class RuntimeState {
    STARTING,
    INDEXING,
    READY,
    DEGRADED,
}
