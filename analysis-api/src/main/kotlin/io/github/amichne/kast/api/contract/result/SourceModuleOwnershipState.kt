package io.github.amichne.kast.api.contract.result

import kotlinx.serialization.Serializable

@Serializable
enum class SourceModuleOwnershipState {
    OWNED,
    OUTSIDE_SOURCE_MODULES,
    NOT_APPLICABLE,
}
