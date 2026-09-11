package io.github.amichne.kast.appserver.runtime

internal data class InitializeRequest(val message: String, val idKey: String)

internal enum class InitializationResponse {
    UNRELATED,
    SUCCESS,
    FAILURE,
}

internal enum class BrokerWebSocketRoute(val path: String) {
    CODEX("/rpc"),
    LEGACY("/"),
}
