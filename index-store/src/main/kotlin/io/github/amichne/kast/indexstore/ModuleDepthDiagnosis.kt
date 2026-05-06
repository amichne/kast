package io.github.amichne.kast.indexstore

import kotlinx.serialization.Serializable

@Serializable
enum class ModuleDepthDiagnosis {
    DEEP,
    SHALLOW,
    PASS_THROUGH,
    MONOLITH,
}
