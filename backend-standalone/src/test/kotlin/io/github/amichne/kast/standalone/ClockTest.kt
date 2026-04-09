package io.github.amichne.kast.standalone

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClockTest {

    @Test
    fun `TestClock starts at zero`() {
        val clock = TestClock()
        assertEquals(0L, clock.nanoTime())
        assertEquals(0L, clock.currentTimeMillis())
    }

    @Test
    fun `TestClock advanceNanos increments nanoTime`() {
        val clock = TestClock()
        clock.advanceNanos(500)
        assertEquals(500L, clock.nanoTime())
        clock.advanceNanos(300)
        assertEquals(800L, clock.nanoTime())
    }

    @Test
    fun `TestClock advanceMillis increments both millis and nanos`() {
        val clock = TestClock()
        clock.advanceMillis(100)
        assertEquals(100L, clock.currentTimeMillis())
        assertEquals(100_000_000L, clock.nanoTime())
    }

    @Test
    fun `TestClock advanceMillis accumulates with advanceNanos`() {
        val clock = TestClock()
        clock.advanceNanos(500_000)
        clock.advanceMillis(10)
        assertEquals(10L, clock.currentTimeMillis())
        assertEquals(10_500_000L, clock.nanoTime())
    }

    @Test
    fun `TestClock rejects negative advance`() {
        val clock = TestClock()
        val nanoError = runCatching { clock.advanceNanos(-1) }
        assertTrue(nanoError.isFailure)
        assertTrue(nanoError.exceptionOrNull() is IllegalArgumentException)

        val millisError = runCatching { clock.advanceMillis(-1) }
        assertTrue(millisError.isFailure)
        assertTrue(millisError.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `Clock SYSTEM returns monotonically increasing values`() {
        val clock = Clock.SYSTEM
        val first = clock.nanoTime()
        val second = clock.nanoTime()
        assertTrue(second >= first, "System clock should not go backwards")

        val millis1 = clock.currentTimeMillis()
        val millis2 = clock.currentTimeMillis()
        assertTrue(millis2 >= millis1, "System clock millis should not go backwards")
    }
}
