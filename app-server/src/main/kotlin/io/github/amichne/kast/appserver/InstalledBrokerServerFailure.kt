package io.github.amichne.kast.appserver

internal enum class InstalledBrokerServerFailure {
    READINESS_REJECTED,
    KAST_QUALIFICATION_REJECTED,
    GRADLE_DEFINITION_REJECTED,
    CATALOG_REJECTED,
    CODEX_QUALIFICATION_REJECTED,
    THREAD_STORE_REJECTED,
    PAYLOAD_LIMIT_EXCEEDED,
    UPSTREAM_REJECTED,
    PUBLIC_SERVER_REJECTED,
}

internal fun InstalledBrokerServerFailure.serverFailure(): BrokerServerFailure =
    when (this) {
        InstalledBrokerServerFailure.READINESS_REJECTED -> BrokerServerFailure.READINESS_REJECTED
        InstalledBrokerServerFailure.KAST_QUALIFICATION_REJECTED -> BrokerServerFailure.KAST_QUALIFICATION_REJECTED
        InstalledBrokerServerFailure.GRADLE_DEFINITION_REJECTED,
        InstalledBrokerServerFailure.CATALOG_REJECTED -> BrokerServerFailure.CATALOG_REJECTED
        InstalledBrokerServerFailure.CODEX_QUALIFICATION_REJECTED -> BrokerServerFailure.CODEX_QUALIFICATION_REJECTED
        InstalledBrokerServerFailure.PAYLOAD_LIMIT_EXCEEDED -> BrokerServerFailure.PAYLOAD_LIMIT_EXCEEDED
        InstalledBrokerServerFailure.THREAD_STORE_REJECTED -> BrokerServerFailure.THREAD_STORE_REJECTED
        InstalledBrokerServerFailure.UPSTREAM_REJECTED -> BrokerServerFailure.UPSTREAM_REJECTED
        InstalledBrokerServerFailure.PUBLIC_SERVER_REJECTED -> BrokerServerFailure.SERVER_REJECTED
    }
