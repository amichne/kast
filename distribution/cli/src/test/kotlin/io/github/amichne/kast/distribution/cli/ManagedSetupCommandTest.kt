package io.github.amichne.kast.distribution.cli

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManagedSetupCommandTest {
    @Test
    fun `public setup admits CLI skill and explicit MCP compositions`() {
        for (arguments in
            listOf(
                listOf("install", "codex"),
                listOf("install", "copilot", "--mcp", "--json"),
                listOf("install", "pi"),
                listOf("install", "skill", "--output", "custom skills", "--json"),
                listOf("versions", "--json"),
                listOf("upgrade", "--channel", "main", "--dry-run", "--json"),
                listOf("upgrade", "--commit", "abcdef123456", "--restart-ide"),
                listOf("upgrade", "--version", "1.2.3", "--idea-home", "/Applications/IDEA.app"),
            )) assertTrue(parseManagementCommand(arguments) is ManagementParsing.Selected, arguments.toString())
    }

    @Test
    fun `retired setup and conflicting selectors reject before an effect is selected`() {
        for (arguments in
            listOf(
                listOf("connect", "codex"),
                listOf("plugin", "codex"),
                listOf("upgrade", "--channel", "main", "--commit", "abcdef123456"),
                listOf("upgrade", "--version", "1.2.3", "--commit", "abcdef123456"),
                listOf("install", "skill", "--force"),
                listOf("install", "skill", "--mcp"),
            )) assertTrue((parseManagementCommand(arguments) as ManagementParsing.Print).error, arguments.toString())
    }
}
