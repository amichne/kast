package io.github.amichne.kast.workspace.service

/** Serializes the complete observation/refresh/publication transition for one workspace. */
class WorkspaceTransitionOwner {
    private val monitor = Any()

    fun <Value> exclusively(transition: () -> Value): Value = synchronized(monitor, transition)
}
