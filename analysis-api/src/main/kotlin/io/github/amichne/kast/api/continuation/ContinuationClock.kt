package io.github.amichne.kast.api.continuation

fun interface ContinuationClock {
    fun nowNanos(): Long

    companion object {
        val System: ContinuationClock = ContinuationClock(java.lang.System::nanoTime)
    }
}
