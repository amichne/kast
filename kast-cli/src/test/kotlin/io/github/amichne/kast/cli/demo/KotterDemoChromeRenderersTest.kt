package io.github.amichne.kast.cli.demo

import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.terminal.inmemory.resolveRerenders
import com.varabyte.kotterx.test.foundation.testSession
import com.varabyte.kotterx.test.runtime.stripFormatting
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KotterDemoChromeRenderersTest {
    @Test
    fun `operation rail renders every act and highlights the active chip`() = testSession { terminal ->
        section {
            renderOperationRail(
                listOf(
                    KotterDemoOperationChip(label = "Rename", active = false),
                    KotterDemoOperationChip(label = "Call Hierarchy", active = false),
                    KotterDemoOperationChip(label = "Find References", active = true),
                ),
            )
        }.run()

        val rail = terminal.resolveRerenders().stripFormatting().firstContentLine()
        assertTrue(rail.contains("Acts"))
        assertTrue(rail.contains("Rename"))
        assertTrue(rail.contains("Call Hierarchy"))
        assertTrue(rail.contains("[Find References]"))
    }

    @Test
    fun `query bar renders the command label and a blinking cursor when requested`() = testSession { terminal ->
        section {
            renderQueryBar(
                KotterDemoQueryBar(
                    renderedCommand = "kast references --symbol io.acme.demo.execute --depth 2",
                    cursorVisible = true,
                ),
            )
        }.run()

        val queryBar = terminal.resolveRerenders().stripFormatting().firstContentLine()
        assertTrue(queryBar.contains("Command"))
        assertTrue(queryBar.contains("kast references --symbol io.acme.demo.execute --depth 2"))
        assertTrue(queryBar.contains("█"))
    }

    @Test
    fun `phase bar and activity indicator render live and complete states without transcript work`() = testSession { terminal ->
        section {
            renderPhaseBar(
                KotterDemoPhaseBar(
                    phases = listOf(
                        KotterDemoPhaseChip(label = "RESOLVE", status = KotterDemoPhaseStatus.COMPLETE),
                        KotterDemoPhaseChip(label = "TRAVERSE", status = KotterDemoPhaseStatus.ACTIVE),
                        KotterDemoPhaseChip(label = "APPLY", status = KotterDemoPhaseStatus.PENDING),
                    ),
                ),
            )
            text(" ")
            renderActivityIndicator(
                KotterDemoActivityIndicator(
                    status = KotterDemoActivityStatus.RUNNING,
                    pulseVisible = false,
                ),
            )
            textLine()
            renderActivityIndicator(
                KotterDemoActivityIndicator(
                    status = KotterDemoActivityStatus.COMPLETE,
                    pulseVisible = false,
                ),
            )
        }.run()

        val lines = terminal.resolveRerenders().stripFormatting().filter(String::isNotBlank)
        assertTrue(lines[0].contains("✓ RESOLVE"))
        assertTrue(lines[0].contains("▶ TRAVERSE"))
        assertTrue(lines[0].contains("APPLY"))
        assertTrue(lines[0].contains("○"))
        assertTrue(lines[1].contains("●"))
        assertFalse(lines[1].contains("○"))
    }

    private fun List<String>.firstContentLine(): String = first(String::isNotBlank)
}
