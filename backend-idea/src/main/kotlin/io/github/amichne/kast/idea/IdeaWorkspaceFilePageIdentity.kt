package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.WorkspaceId
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.query.WorkspaceFileKindDomain
import io.github.amichne.kast.api.validation.WorkspaceFileSnapshotToken

internal data class IdeaWorkspaceFilePageIdentity(
    val workspaceId: WorkspaceId,
    val snapshotToken: WorkspaceFileSnapshotToken,
    val kindDomain: WorkspaceFileKindDomain,
    val moduleIdentity: IdeaWorkspaceModuleIdentity,
    val pageSize: PositiveInt,
)
