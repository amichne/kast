package io.github.amichne.kast.appserver

import kotlinx.serialization.Serializable

@Serializable
enum class BrokerServerFailure {
    UNAVAILABLE,
    CONFIGURATION_REJECTED,
    KAST_EXECUTABLE_REJECTED,
    USER_HOME_REJECTED,
    CODEX_EXECUTABLE_REJECTED,
    CODEX_HOME_REJECTED,
    STATE_DIRECTORY_REJECTED,
    SOCKET_PATH_REJECTED,
    PROVIDER_CONFIGURATION_REJECTED,
    PROTOCOL_CONFIGURATION_REJECTED,
    APP_SERVER_DISABLED,
    KAST_QUALIFICATION_REJECTED,
    CATALOG_REJECTED,
    CODEX_QUALIFICATION_REJECTED,
    THREAD_STORE_REJECTED,
    UPSTREAM_REJECTED,
    SERVER_REJECTED,
    READINESS_REJECTED,
    INTERRUPTED,
}

fun BrokerServerFailure.outputReason(): String =
    "broker-${name.lowercase().replace('_', '-')}"

sealed interface BrokerServerRun {
    data object Stopped : BrokerServerRun
    data class Rejected(val failure: BrokerServerFailure) : BrokerServerRun
}

fun interface BrokerServerRunner {
    fun serve(): BrokerServerRun
}

object UnavailableBrokerServerRunner : BrokerServerRunner {
    override fun serve(): BrokerServerRun = BrokerServerRun.Rejected(BrokerServerFailure.UNAVAILABLE)
}
