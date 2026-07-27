package io.github.amichne.kast.idea

import io.github.amichne.kast.api.continuation.ContinuationProjection

internal data class IdeaWorkspaceFileSnapshotProjection(
    val inventory: IdeaWorkspaceFileInventorySnapshot,
) : ContinuationProjection()
