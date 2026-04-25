package io.github.amichne.kast.cli.demo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KotterDemoLayoutDualPaneTest {
    private val subject = KotterDemoLayoutCalculator()

    @Test
    fun `wide terminal chooses dual pane layout`() {
        val decision = subject.layout(request(200))

        val ready = decision as KotterDemoLayoutDecision.Ready
        assertNotNull(ready.dualPane)
        assertEquals(98, ready.dualPane!!.paneWidth)
        assertEquals(197, ready.dualPane.totalWidth)
        assertTrue(!ready.fallbackToSingle)
    }

    @Test
    fun `medium terminal falls back to single pane`() {
        val decision = subject.layout(request(100))

        val ready = decision as KotterDemoLayoutDecision.Ready
        assertNull(ready.dualPane)
        assertTrue(ready.fallbackToSingle)
    }

    @Test
    fun `narrow terminal halts before rendering`() {
        val decision = subject.layout(request(70))

        assertTrue(decision is KotterDemoLayoutDecision.Halted)
    }

    private fun request(width: Int): KotterDemoLayoutRequest =
        KotterDemoLayoutRequest(
            terminalWidth = width,
            operations = listOf("References", "Rename", "Call Graph"),
            activeOperation = "References",
            query = "kast demo",
            cursorVisible = false,
            mode = KotterDemoLayoutMode.DualPane,
        )
}
