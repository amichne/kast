package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.WorkspaceId
import io.github.amichne.kast.api.contract.query.WorkspaceFileKindDomain

internal data class IdeaWorkspaceFileSnapshotIdentity(
    val workspaceId: WorkspaceId,
    val kindDomain: WorkspaceFileKindDomain,
)
