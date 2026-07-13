package io.github.amichne.kast.api.contract.result

import kotlinx.serialization.Serializable

@Serializable
enum class FileAnalysisState {
    ANALYZED,
    PENDING_INDEX,
    OUTSIDE_SOURCE_MODULES,
    MISSING_ON_DISK,
    BACKEND_FAILURE,
}
