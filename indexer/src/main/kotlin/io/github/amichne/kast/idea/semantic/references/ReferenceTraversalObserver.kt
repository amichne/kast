package io.github.amichne.kast.idea

internal fun interface ReferenceTraversalObserver {
    fun closed()

    fun referenceProcessed(
        filePath: String,
        leafOffset: Int,
        referenceIndex: Int,
        referenceCount: Int,
    ) = Unit

    companion object {
        val Disabled: ReferenceTraversalObserver = ReferenceTraversalObserver {}
    }
}
