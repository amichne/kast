package io.github.amichne.kast.appserver.protocol.codex

import kotlinx.serialization.Serializable

/** Exact broker-owned failures; the existing machine codes remain stable. */
@Serializable
internal enum class CodexToolTerminalFailure {
    CATALOG_INCOMPATIBLE,
    BROKER_OVERLOADED_IN_FLIGHT_CALLS_PER_CONNECTION,
    DUPLICATE_INVOCATION,
    BROKER_ACTIVITY_UNAVAILABLE,
    BROKER_OVERLOADED_MAXIMUM_TOOL_RESULT_BYTES,
}
