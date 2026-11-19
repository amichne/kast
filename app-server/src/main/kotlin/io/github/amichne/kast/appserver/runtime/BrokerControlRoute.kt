package io.github.amichne.kast.appserver.runtime

internal enum class BrokerControlRoute(val path: String) {
    CODEX("/"),
    RUNTIME("/kast-runtime"),
    MANAGEMENT(io.github.amichne.kast.appserver.DaemonManagementProtocol.route),
    SEMANTIC("/kast-semantic"),
    PUBLIC_INVOCATION(io.github.amichne.kast.appserver.PublicInvocationProtocol.route),
}
