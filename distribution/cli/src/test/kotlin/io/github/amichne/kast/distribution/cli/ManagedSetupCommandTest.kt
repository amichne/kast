package io.github.amichne.kast.distribution.cli

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManagedSetupCommandTest {
    @Test
    fun `public setup admits skill export and release selection`() {
        for (arguments in
            listOf(
                listOf("install", "skill", "--output", "custom skills", "--json"),
                listOf("versions", "--json"),
                listOf("upgrade", "--channel", "preview", "--dry-run", "--json"),
                listOf("upgrade", "--commit", "abcdef123456"),
                listOf("upgrade", "--version", "1.2.3"),
                listOf("upgrade", "--repair"),
            )) assertTrue(parseManagementCommand(arguments) is ManagementParsing.Selected, arguments.toString())
    }

    @Test
    fun `retired setup and conflicting selectors reject before an effect is selected`() {
        for (arguments in
            listOf(
                listOf("connect", "codex"),
                listOf("plugin", "codex"),
                listOf("upgrade", "--channel", "preview", "--commit", "abcdef123456"),
                listOf("upgrade", "--version", "1.2.3", "--commit", "abcdef123456"),
                listOf("install", "skill", "--force"),
                listOf("install", "skill", "--mcp"),
                // An agent installs the Kast plugin with its own commands, not through a Kast harness install.
                listOf("install", "codex"),
                listOf("install", "copilot"),
                listOf("install", "pi"),
            )) assertTrue((parseManagementCommand(arguments) as ManagementParsing.Print).error, arguments.toString())
    }
}
