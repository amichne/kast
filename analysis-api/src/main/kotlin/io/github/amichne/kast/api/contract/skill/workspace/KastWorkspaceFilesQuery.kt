package io.github.amichne.kast.api.contract.skill

import io.github.amichne.kast.api.contract.query.WorkspaceFileKindDomain
import kotlinx.serialization.Serializable

@Serializable
data class KastWorkspaceFilesQuery(
    val workspaceRoot: String,
    val moduleName: String? = null,
    val includeFiles: Boolean = false,
    val maxFilesPerModule: Int? = null,
    val kindDomain: WorkspaceFileKindDomain = WorkspaceFileKindDomain.MIXED,
    val snapshotToken: String? = null,
    val pageToken: String? = null,
)
