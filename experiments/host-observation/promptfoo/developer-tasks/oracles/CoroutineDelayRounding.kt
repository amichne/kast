package oracle

import kotlinx.coroutines.toDelayMillis
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

fun main() {
    val edgeCases = listOf(
        Duration.INFINITE to Long.MAX_VALUE,
        -Duration.INFINITE to 0L,
        (-1).milliseconds to 0L,
        Duration.ZERO to 0L,
        (998.milliseconds + 75_909.nanoseconds) to 999L,
        (Long.MAX_VALUE / 10).milliseconds to Long.MAX_VALUE / 10,
        ((Long.MAX_VALUE / 2) - 1).milliseconds to (Long.MAX_VALUE / 2) - 1,
    )
    for ((duration, expected) in edgeCases) {
        check(duration.toDelayMillis() == expected) { "Wrong delay for $duration" }
    }
    for (millis in listOf(0, 1, 2, 317, 999, 65_537)) {
        for (nanos in listOf(0, 1, 499_999, 500_000, 999_999)) {
            val duration = millis.milliseconds + nanos.nanoseconds
            val expected = millis.toLong() + if (nanos == 0) 0 else 1
            check(duration.toDelayMillis() == expected) { "Wrong delay for $duration" }
        }
    }
    println("PASS")
}
