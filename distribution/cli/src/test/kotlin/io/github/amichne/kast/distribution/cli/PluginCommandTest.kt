package io.github.amichne.kast.distribution.cli

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginCommandTest {
    @Test
    fun `retired plugin setup rejects and names the install replacement`() {
        for (arguments in listOf(listOf("plugin"), listOf("plugin", "codex"), listOf("plugin", "codex", "--json"))) {
            val result = parseManagementCommand(arguments) as ManagementParsing.Print
            assertTrue(result.error)
            assertTrue(result.text.contains("kast install"))
        }
    }

    @Test
    fun `retired plugin help names the supported setup command`() {
        val result = parseManagementCommand(listOf("plugin", "--help")) as ManagementParsing.Print
        assertTrue(result.error)
        assertTrue(result.text.contains("kast install"))
    }

    @Test
    fun `retired plugin syntax never selects an effect for another harness`() {
        for (arguments in listOf(listOf("plugin", "copilot"), listOf("plugin", "pi"), listOf("plugin", "--json"))) {
            val result = parseManagementCommand(arguments) as ManagementParsing.Print
            assertTrue(result.error)
            assertTrue(result.text.contains("kast install"))
        }
    }
}
