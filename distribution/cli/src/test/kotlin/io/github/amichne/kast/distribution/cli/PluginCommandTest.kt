package io.github.amichne.kast.distribution.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginCommandTest {
    @Test
    fun `plugin command lists harnesses or selects typed Codex installation`() {
        assertEquals(
            ManagementParsing.Selected(ManagementCommand.ListPluginHarnesses),
            parseManagementCommand(listOf("plugin")),
        )
        assertEquals(
            ManagementParsing.Selected(ManagementCommand.Plugin(PluginHarness.CODEX, false)),
            parseManagementCommand(listOf("plugin", "codex")),
        )
        assertEquals(
            ManagementParsing.Selected(ManagementCommand.Plugin(PluginHarness.CODEX, true)),
            parseManagementCommand(listOf("plugin", "codex", "--json")),
        )
    }

    @Test
    fun `plugin help describes the supported Codex harness`() {
        val help = parseManagementCommand(listOf("plugin", "--help")) as ManagementParsing.Print
        assertFalse(help.error)
        assertTrue(help.text.contains("codex"))
    }

    @Test
    fun `unsupported plugin harness rejects without selecting an effect`() {
        assertTrue((parseManagementCommand(listOf("plugin", "copilot")) as ManagementParsing.Print).error)
        assertTrue((parseManagementCommand(listOf("plugin", "pi")) as ManagementParsing.Print).error)
        assertTrue((parseManagementCommand(listOf("plugin", "--json")) as ManagementParsing.Print).error)
    }
}
