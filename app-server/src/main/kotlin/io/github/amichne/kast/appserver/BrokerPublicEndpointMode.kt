package io.github.amichne.kast.appserver

import io.github.amichne.kast.distribution.contract.configuration.ConfigurationParameter
import io.github.amichne.kast.kernel.Refinement

/** Admitted discovery policy shared by installation and broker configuration. */
enum class BrokerPublicEndpointMode(val configurationValue: String) {
    PRIVATE("private"),
    CODEX_CONTROL("codex-control");

    companion object {
        fun admit(raw: String?): Refinement<BrokerPublicEndpointMode, AppServerConfigurationFailure> =
            when (raw ?: ConfigurationParameter.APP_SERVER_PUBLIC_ENDPOINT.declaration().defaultValue) {
                "private" -> Refinement.Refined(PRIVATE)
                "codex-control" -> Refinement.Refined(CODEX_CONTROL)
                else -> Refinement.Rejected(AppServerConfigurationFailure.INVALID_PUBLIC_ENDPOINT)
            }
    }
}
