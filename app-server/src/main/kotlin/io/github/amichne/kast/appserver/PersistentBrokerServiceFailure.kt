package io.github.amichne.kast.appserver

enum class PersistentBrokerServiceFailure {
    UNAVAILABLE,
    CONFIGURATION_REJECTED,
    KAST_QUALIFICATION_REJECTED,
    CATALOG_REJECTED,
    CODEX_QUALIFICATION_REJECTED,
    THREAD_STORE_REJECTED,
    UPSTREAM_REJECTED,
    SERVER_REJECTED,
    KAST_EXECUTABLE_UNAVAILABLE,
    CODEX_EXECUTABLE_UNAVAILABLE,
    CODEX_HOME_REJECTED,
    USER_HOME_REJECTED,
    JAVA_RUNTIME_UNAVAILABLE,
    STATE_DIRECTORY_REJECTED,
    PAYLOAD_LIMIT_EXCEEDED,
    SERVICE_LOCK_REJECTED,
    SERVICE_OBSERVATION_REJECTED,
    SERVICE_RETIREMENT_REJECTED,
    SERVICE_SUBMISSION_REJECTED,
    READINESS_REJECTED,
    PUBLIC_SOCKET_OWNED,
    SOCKET_PROBE_REJECTED,
    SOCKET_PATH_REJECTED,
    PROVIDER_CONFIGURATION_REJECTED,
    PROTOCOL_CONFIGURATION_REJECTED,
    LAUNCHCTL_TIMED_OUT,
    STARTUP_TIMED_OUT,
    INTERRUPTED,
    DISABLED,
}

internal fun BrokerServerFailure.persistentServiceFailure(): PersistentBrokerServiceFailure =
    when (this) {
        BrokerServerFailure.UNAVAILABLE -> PersistentBrokerServiceFailure.UNAVAILABLE
        BrokerServerFailure.CONFIGURATION_REJECTED -> PersistentBrokerServiceFailure.CONFIGURATION_REJECTED
        BrokerServerFailure.KAST_EXECUTABLE_REJECTED -> PersistentBrokerServiceFailure.KAST_EXECUTABLE_UNAVAILABLE
        BrokerServerFailure.USER_HOME_REJECTED -> PersistentBrokerServiceFailure.USER_HOME_REJECTED
        BrokerServerFailure.CODEX_EXECUTABLE_REJECTED -> PersistentBrokerServiceFailure.CODEX_EXECUTABLE_UNAVAILABLE
        BrokerServerFailure.CODEX_HOME_REJECTED -> PersistentBrokerServiceFailure.CODEX_HOME_REJECTED
        BrokerServerFailure.PAYLOAD_LIMIT_EXCEEDED -> PersistentBrokerServiceFailure.PAYLOAD_LIMIT_EXCEEDED
        BrokerServerFailure.STATE_DIRECTORY_REJECTED -> PersistentBrokerServiceFailure.STATE_DIRECTORY_REJECTED
        BrokerServerFailure.SOCKET_PATH_REJECTED -> PersistentBrokerServiceFailure.SOCKET_PATH_REJECTED
        BrokerServerFailure.PROVIDER_CONFIGURATION_REJECTED ->
            PersistentBrokerServiceFailure.PROVIDER_CONFIGURATION_REJECTED
        BrokerServerFailure.PROTOCOL_CONFIGURATION_REJECTED ->
            PersistentBrokerServiceFailure.PROTOCOL_CONFIGURATION_REJECTED
        BrokerServerFailure.APP_SERVER_DISABLED -> PersistentBrokerServiceFailure.DISABLED
        BrokerServerFailure.KAST_QUALIFICATION_REJECTED -> PersistentBrokerServiceFailure.KAST_QUALIFICATION_REJECTED
        BrokerServerFailure.CATALOG_REJECTED -> PersistentBrokerServiceFailure.CATALOG_REJECTED
        BrokerServerFailure.CODEX_QUALIFICATION_REJECTED -> PersistentBrokerServiceFailure.CODEX_QUALIFICATION_REJECTED
        BrokerServerFailure.THREAD_STORE_REJECTED -> PersistentBrokerServiceFailure.THREAD_STORE_REJECTED
        BrokerServerFailure.UPSTREAM_REJECTED -> PersistentBrokerServiceFailure.UPSTREAM_REJECTED
        BrokerServerFailure.SERVER_REJECTED -> PersistentBrokerServiceFailure.SERVER_REJECTED
        BrokerServerFailure.READINESS_REJECTED -> PersistentBrokerServiceFailure.READINESS_REJECTED
        BrokerServerFailure.INTERRUPTED -> PersistentBrokerServiceFailure.INTERRUPTED
    }
