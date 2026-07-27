package io.github.amichne.kast.idea

internal fun interface ReferenceSearchClock {
    fun nanoTime(): Long

    companion object {
        val System: ReferenceSearchClock = ReferenceSearchClock { java.lang.System.nanoTime() }
    }
}
