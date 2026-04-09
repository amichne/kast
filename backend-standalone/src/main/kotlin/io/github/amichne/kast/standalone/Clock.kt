package io.github.amichne.kast.standalone

/**
 * Abstraction over system time sources, enabling deterministic time control in tests.
 *
 * Production code should use [SYSTEM]; tests should inject [TestClock] for
 * repeatable, non-flaky timing assertions.
 */
internal interface Clock {
    fun nanoTime(): Long
    fun currentTimeMillis(): Long

    companion object {
        val SYSTEM: Clock = object : Clock {
            override fun nanoTime(): Long = System.nanoTime()
            override fun currentTimeMillis(): Long = System.currentTimeMillis()
        }
    }
}

/**
 * Test implementation with manually advanceable time.
 *
 * Both [nanoTime] and [currentTimeMillis] start at zero and advance only
 * when explicitly requested, making timing assertions deterministic.
 */
internal class TestClock(
    private var nanos: Long = 0L,
    private var millis: Long = 0L,
) : Clock {
    override fun nanoTime(): Long = nanos
    override fun currentTimeMillis(): Long = millis

    fun advanceNanos(amount: Long) {
        require(amount >= 0) { "Cannot advance time backwards: $amount" }
        nanos += amount
    }

    fun advanceMillis(amount: Long) {
        require(amount >= 0) { "Cannot advance time backwards: $amount" }
        millis += amount
        nanos += amount * 1_000_000
    }
}
