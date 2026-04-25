package io.github.amichne.kast.cli.demo

import io.github.amichne.kast.cli.DemoTextMatchCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DualPaneCaptureTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `capture round trips through fixture json`() {
        val capture = DualPaneCapture(
            scenario = DualPaneScenario(
                rounds = listOf(
                    DualPaneRound(
                        title = "References",
                        leftCommand = "grep -rn execute",
                        rightCommand = "kast references --symbol execute",
                        leftLines = listOf(DualPaneLeftLine("A.kt:1 execute", DemoTextMatchCategory.LIKELY_CORRECT)),
                        rightLines = listOf(KotterDemoTranscriptLine("A.kt:1 CALL", KotterDemoStreamTone.CONFIRMED)),
                        leftFooter = "⚑ 1 hit · 0 type info · 0 scope",
                        rightFooter = "✓ 1 ref · typed · scoped · proven",
                        scoreboard = listOf(
                            ScoreboardRow(
                                metric = "Type information",
                                grepValue = "none",
                                kastValue = "full FQN + kind",
                                delta = "NEW",
                                isNewCapability = true,
                            ),
                        ),
                    ),
                ),
            ),
            symbolFqn = "io.example.execute",
        )
        val path = tempDir.resolve("capture.json")

        saveCapture(path, capture)

        assertEquals(capture, loadCapture(path))
    }
}
