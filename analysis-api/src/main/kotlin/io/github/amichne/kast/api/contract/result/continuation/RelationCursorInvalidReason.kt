package io.github.amichne.kast.api.contract.result

import kotlinx.serialization.Serializable

@Serializable
enum class RelationCursorInvalidReason {
    UNKNOWN_HANDLE,
    FAMILY_MISMATCH,
    QUERY_MISMATCH,
}
