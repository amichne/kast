package io.github.amichne.kast.cli.demo

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class Tick(
    val side: Side,
    val lineIndex: Int,
    val lineCount: Int = 1,
)

internal enum class Side {
    LEFT,
    RIGHT,
    SCOREBOARD,
}

internal class DualPaneScheduler(
    val leftCadenceMs: Long = 150L,
    val rightCadenceMs: Long = 200L,
    val scoreboardRevealMs: Long = 80L,
    val roundHoldMs: Long = 0L,
    val leftBatchSize: Int = 3,
) {
    suspend fun playRound(
        leftLineCount: Int,
        rightLineCount: Int,
        scoreboardRowCount: Int,
        onTick: (Tick) -> Unit,
    ) {
        coroutineScope {
            launch {
                var index = 0
                while (index < leftLineCount) {
                    val lineCount = leftBatchSize.coerceAtLeast(1).coerceAtMost(leftLineCount - index)
                    delay(leftCadenceMs)
                    onTick(Tick(Side.LEFT, index, lineCount))
                    index += lineCount
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
