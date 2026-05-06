package io.github.amichne.kast.indexstore.api.metrics.module

import kotlinx.serialization.Serializable

@Serializable
enum class ModuleDepthDiagnosis {
    DEEP,
    SHALLOW,
    PASS_THROUGH,
    MONOLITH,
}
