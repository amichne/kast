package io.github.amichne.kast.api.contract.result

import kotlinx.serialization.Serializable

@Serializable
enum class ContainingSymbolUnavailableReason {
    NO_SEMANTIC_OWNER,
}
