package io.github.amichne.kast.cli.demo

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class Tick(
    val side: Side,
    val lineIndex: Int,
)

internal enum class Side {
    LEFT,
    RIGHT,
    SCOREBOARD,
}

internal class DualPaneScheduler(
    val leftCadenceMs: Long = 50L,
    val rightCadenceMs: Long = 300L,
    val scoreboardRevealMs: Long = 120L,
    val roundHoldMs: Long = 1500L,
) {
    suspend fun playRound(
        leftLineCount: Int,
        rightLineCount: Int,
        scoreboardRowCount: Int,
        onTick: (Tick) -> Unit,
    ) {
        coroutineScope {
            launch {
                repeat(leftLineCount) { index ->
                    delay(leftCadenceMs)
                    onTick(Tick(Side.LEFT, index))
                }
            }
            launch {
                repeat(rightLineCount) { index ->
                    delay(rightCadenceMs)
                    onTick(Tick(Side.RIGHT, index))
                }
            }
        }
        repeat(scoreboardRowCount) { index ->
            delay(scoreboardRevealMs)
            onTick(Tick(Side.SCOREBOARD, index))
        }
        delay(roundHoldMs)
    }
}
