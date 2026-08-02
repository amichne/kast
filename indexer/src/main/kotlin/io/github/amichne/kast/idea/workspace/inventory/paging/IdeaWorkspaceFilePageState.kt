package io.github.amichne.kast.idea

import io.github.amichne.kast.api.continuation.ContinuationOwnedState

internal class IdeaWorkspaceFilePageState(
    val generation: IdeaWorkspaceInventoryGeneration,
    val moduleIdentity: IdeaWorkspaceModuleIdentity,
    nextOffset: Int,
) : ContinuationOwnedState() {
    var nextOffset: Int = nextOffset
        private set

    init {
        require(nextOffset >= 0) { "IDEA workspace page offset must be nonnegative" }
    }

    fun advanceTo(offset: Int) {
        require(offset > nextOffset) { "IDEA workspace page offset must advance" }
        nextOffset = offset
    }
}
