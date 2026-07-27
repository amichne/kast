package io.github.amichne.kast.idea

internal fun interface IdeaReadEpochObserver {
    fun entered(kind: IdeaReadEpochKind)

    companion object {
        val Disabled: IdeaReadEpochObserver = IdeaReadEpochObserver {}
    }
}
