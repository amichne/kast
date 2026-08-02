package io.github.amichne.kast.idea

import io.github.amichne.kast.api.validation.WorkspaceFileSnapshotToken

internal data class IdeaWorkspaceFileSnapshot(
    val token: WorkspaceFileSnapshotToken,
    val inventory: IdeaWorkspaceFileInventorySnapshot,
)
