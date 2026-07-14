package io.github.amichne.kast.idea

internal fun interface ReferenceTraversalObserver {
    fun closed()

    companion object {
        val Disabled: ReferenceTraversalObserver = ReferenceTraversalObserver {}
    }
}
