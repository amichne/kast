package io.github.amichne.kast.cli.demo

import com.varabyte.kotter.runtime.terminal.inmemory.resolveRerenders
import com.varabyte.kotterx.test.foundation.testSession
import com.varabyte.kotterx.test.runtime.stripFormatting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScoreboardRendererTest {
    @Test
    fun `scoreboard grid keeps four columns and marks new capabilities`() {
        val lines = scoreboardGridLines(
            rows = listOf(
                ScoreboardRow("Noise reduction", "38 hits", "6 refs", "84% less", isNewCapability = false),
                ScoreboardRow("Type information", "none", "full FQN + kind", "NEW", isNewCapability = true),
            ),
            totalWidth = 100,
        )

        assertEquals(3, lines.first().count { it == '┬' })
        assertTrue(lines.any { it.contains("Metric") && it.contains("grep") && it.contains("kast") && it.contains("Δ") })
        assertTrue(lines.any { it.contains("84% less") })
        assertTrue(lines.any { it.contains("★ NEW") })
    }

    @Test
    fun `scoreboard renderer colors the new badge`() = testSession { terminal ->
        section {
            renderScoreboard(
                rows = listOf(ScoreboardRow("Type information", "none", "full FQN + kind", "NEW", isNewCapability = true)),
                totalWidth = 72,
            )
        }.run()

        val rendered = terminal.resolveRerenders()
        val plain = rendered.stripFormatting()
        assertTrue(plain.any { it.contains("★ NEW") }, "expected NEW badge in plain output: $plain")
    }
}
