package io.github.amichne.kast.cli.demo

import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DualPaneSchedulerTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `left stream outruns the right stream before scoreboard reveal`() = runTest {
        val scheduler = DualPaneScheduler(roundHoldMs = 0)
        val ticks = mutableListOf<Tick>()

        val job = launch {
            scheduler.playRound(leftLineCount = 12, rightLineCount = 2, scoreboardRowCount = 1) { tick ->
                ticks += tick
            }
        }

        advanceTimeBy(100)
        runCurrent()
        assertEquals(0, ticks.count { it.side == Side.LEFT })
        assertEquals(0, ticks.count { it.side == Side.RIGHT })

        advanceTimeBy(50)
        runCurrent()
        assertEquals(3, ticks.filter { it.side == Side.LEFT }.sumOf(Tick::lineCount))
        assertEquals(0, ticks.count { it.side == Side.RIGHT })

        advanceTimeBy(450)
        runCurrent()
        assertEquals(12, ticks.filter { it.side == Side.LEFT }.sumOf(Tick::lineCount))
        assertEquals(2, ticks.count { it.side == Side.RIGHT })
        assertEquals(0, ticks.count { it.side == Side.SCOREBOARD })

        advanceTimeBy(120)
        runCurrent()
        assertEquals(1, ticks.count { it.side == Side.SCOREBOARD })
        job.cancel()
    }
}
