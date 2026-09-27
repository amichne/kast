package io.github.amichne.kast.query.service

/** Monotonic clock isolated at the evaluator effect boundary. */
fun interface QueryNanoClock {
    fun now(): Long
}

internal object SystemQueryNanoClock : QueryNanoClock {
    override fun now(): Long = System.nanoTime()
}
