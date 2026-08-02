package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.query.WorkspaceFileKindDomain

internal fun interface IdeaWorkspaceFileInventory {
    fun snapshot(kindDomain: WorkspaceFileKindDomain): IdeaWorkspaceFileInventorySnapshot
}

internal data class IdeaWorkspaceFileInventoryRevision(
    val projectRoots: Long,
    val vfsStructure: Long,
)
