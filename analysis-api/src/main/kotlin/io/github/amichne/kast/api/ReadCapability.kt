package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
enum class ReadCapability {
    RESOLVE_SYMBOL,
    FIND_REFERENCES,
    CALL_HIERARCHY,
    DIAGNOSTICS,
}
