package io.github.amichne.kast.idea

internal fun interface ContinuationClock {
    fun nowNanos(): Long

    companion object {
        val System: ContinuationClock = ContinuationClock(java.lang.System::nanoTime)
    }
}
