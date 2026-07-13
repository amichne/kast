package io.github.amichne.kast.api.contract.mutation

import kotlinx.serialization.Serializable

@Serializable
enum class KastMutationProgressStage {
    IDENTITY_RESOLUTION,
    EDIT_APPLICATION,
    WORKSPACE_REFRESH,
    IMPORT_OPTIMIZATION,
    DIAGNOSTICS,
}
