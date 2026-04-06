package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
enum class MutationCapability {
    RENAME,
    APPLY_EDITS,
    FILE_OPERATIONS,
    OPTIMIZE_IMPORTS,
    REFRESH_WORKSPACE,
}
