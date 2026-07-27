package io.github.amichne.kast.api.contract.query

import kotlinx.serialization.Serializable

@Serializable
enum class WorkspaceFileKindDomain {
    SOURCE_ONLY,
    SCRIPT_ONLY,
    MIXED,
}
