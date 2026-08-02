package io.github.amichne.kast.idea

import io.github.amichne.kast.api.continuation.ContinuationOwnedState

internal data class IdeaWorkspaceFileSnapshotState(
    val inventory: IdeaWorkspaceFileInventorySnapshot,
) : ContinuationOwnedState()
