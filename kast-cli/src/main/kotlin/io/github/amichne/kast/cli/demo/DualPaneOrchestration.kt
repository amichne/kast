package io.github.amichne.kast.cli.demo

import com.varabyte.kotter.foundation.liveVarOf
import com.varabyte.kotter.foundation.runUntilSignal
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.Session
import kotlinx.coroutines.delay

internal fun Session.runDualPaneSession(
    scenario: DualPaneScenario,
    layout: KotterDemoDualPaneLayout,
    scheduler: DualPaneScheduler = DualPaneScheduler(),
) {
    if (scenario.rounds.isEmpty()) return

    var activeRound by liveVarOf(scenario.rounds.first())
    var leftVisible by liveVarOf<List<DualPaneLeftLine>>(emptyList())
    var rightVisible by liveVarOf<List<KotterDemoTranscriptLine>>(emptyList())
    var scoreboardVisible by liveVarOf<List<ScoreboardRow>>(emptyList())

    section {
        renderPanel(
            title = "Live Demo",
            panelContentWidth = (layout.totalWidth - 4).coerceAtLeast(1),
            bodyLines = listOf(activeRound.title),
        )
        textLine()
        renderDualTranscriptPanel(
            leftHeader = activeRound.leftCommand,
            leftLines = leftVisible,
            rightHeader = activeRound.rightCommand,
            rightLines = rightVisible,
            paneWidth = layout.paneWidth,
            paneHeight = dualPaneBodyHeight(height),
            leftFooter = activeRound.leftFooter,
            rightFooter = activeRound.rightFooter,
            gap = layout.gap,
        )
        if (scoreboardVisible.isNotEmpty()) {
            textLine()
            renderScoreboard(scoreboardVisible, layout.totalWidth)
        }
    }.runUntilSignal {
        for (round in scenario.rounds) {
            activeRound = round
            leftVisible = emptyList()
            rightVisible = emptyList()
            scoreboardVisible = emptyList()

            scheduler.playRound(
                leftLineCount = round.leftLines.size,
                rightLineCount = round.rightLines.size,
                scoreboardRowCount = round.scoreboard.size,
            ) { tick ->
                when (tick.side) {
                    Side.LEFT -> leftVisible = leftVisible + round.leftLines[tick.lineIndex]
                    Side.RIGHT -> rightVisible = rightVisible + round.rightLines[tick.lineIndex]
                    Side.SCOREBOARD -> scoreboardVisible = scoreboardVisible + round.scoreboard[tick.lineIndex]
                }
            }
            delay(0)
        }
        signal()
    }
}

private fun dualPaneBodyHeight(terminalHeight: Int): Int =
    (terminalHeight.coerceIn(1, MAX_TERMINAL_HEIGHT) - DUAL_PANE_CHROME_LINES).coerceAtLeast(MIN_DUAL_PANE_BODY_LINES)

private const val DUAL_PANE_CHROME_LINES: Int = 18
private const val MIN_DUAL_PANE_BODY_LINES: Int = 6
