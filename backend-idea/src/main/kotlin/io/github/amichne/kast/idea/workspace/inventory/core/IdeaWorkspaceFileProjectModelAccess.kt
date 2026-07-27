package io.github.amichne.kast.idea

internal interface IdeaWorkspaceFileProjectModelAccess {
    val isIndexing: Boolean

    fun read(): IdeaWorkspaceFileProjectModel
}
