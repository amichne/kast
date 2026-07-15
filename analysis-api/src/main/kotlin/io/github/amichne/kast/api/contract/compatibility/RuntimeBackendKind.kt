package io.github.amichne.kast.api.contract.compatibility

import kotlinx.serialization.Serializable

@Serializable
enum class RuntimeBackendKind {
    IDEA,
    HEADLESS,
}
