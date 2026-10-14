package io.github.amichne.kast.distribution.contract.configuration

import io.github.amichne.kast.kernel.Refinement

enum class BrokerPublicEndpointModeFailure {
    INVALID_PUBLIC_ENDPOINT
}

/** Admitted discovery policy shared by installation and broker configuration. */
enum class BrokerPublicEndpointMode(val configurationValue: String) {
    PRIVATE("private"),
    CODEX_CONTROL("codex-control");

    companion object {
        fun admit(raw: String?): Refinement<BrokerPublicEndpointMode, BrokerPublicEndpointModeFailure> =
            when (raw ?: ConfigurationParameter.APP_SERVER_PUBLIC_ENDPOINT.declaration().defaultValue) {
                "private" -> Refinement.Refined(PRIVATE)
                "codex-control" -> Refinement.Refined(CODEX_CONTROL)
                else -> Refinement.Rejected(BrokerPublicEndpointModeFailure.INVALID_PUBLIC_ENDPOINT)
            }
    }
}
