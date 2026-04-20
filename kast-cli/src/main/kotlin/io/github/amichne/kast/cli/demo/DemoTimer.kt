package io.github.amichne.kast.cli.demo

import kotlin.time.Duration
import kotlin.time.TimeSource

/** Result of a timed block. Carries a value plus the wall-clock duration. */
internal data class Timed<out T>(val value: T, val elapsed: Duration)

/** Run [block] and return its [Result] alongside the elapsed [Duration]. */
internal suspend fun <T> timed(block: suspend () -> T): Timed<Result<T>> {
    val mark = TimeSource.Monotonic.markNow()
    val outcome = runCatching { block() }
    return Timed(outcome, mark.elapsedNow())
}
