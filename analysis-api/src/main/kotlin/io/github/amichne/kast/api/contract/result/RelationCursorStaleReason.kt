package io.github.amichne.kast.api.contract.result

import kotlinx.serialization.Serializable

@Serializable
enum class RelationCursorStaleReason {
    GENERATION_CHANGED,
    EXPIRED,
}
