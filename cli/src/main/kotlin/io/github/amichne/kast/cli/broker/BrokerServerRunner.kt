package io.github.amichne.kast.cli.broker

import kotlinx.serialization.Serializable

@Serializable
enum class BrokerServerFailure {
    UNAVAILABLE,
    CONFIGURATION_REJECTED,
    KAST_QUALIFICATION_REJECTED,
    CATALOG_REJECTED,
    CODEX_QUALIFICATION_REJECTED,
    THREAD_STORE_REJECTED,
    UPSTREAM_REJECTED,
    SERVER_REJECTED,
    READINESS_REJECTED,
    INTERRUPTED,
}

internal fun BrokerServerFailure.outputReason(): String =
    "broker-${name.lowercase().replace('_', '-')}"

sealed interface BrokerServerRun {
    data object Stopped : BrokerServerRun
    data class Rejected(val failure: BrokerServerFailure) : BrokerServerRun
}

fun interface BrokerServerRunner {
    fun serve(): BrokerServerRun
}

internal object UnavailableBrokerServerRunner : BrokerServerRunner {
    override fun serve(): BrokerServerRun = BrokerServerRun.Rejected(BrokerServerFailure.UNAVAILABLE)
}
