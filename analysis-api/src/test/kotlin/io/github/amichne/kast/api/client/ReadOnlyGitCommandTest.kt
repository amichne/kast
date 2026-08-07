package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.contract.NonBlankString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReadOnlyGitCommandTest {
    @Test
    fun `typed Git read command extracts only at the process boundary`() {
        val command = ReadOnlyGitCommand.workspacePrefix()
        val builder = command.processBuilder()

        assertEquals(listOf("git", "rev-parse", "--show-prefix"), builder.command())
        assertEquals("0", builder.environment()["GIT_OPTIONAL_LOCKS"])
    }

    @Test
    fun `dynamic operands cannot be parsed as Git options`() {
        assertEquals(
            listOf("git", "rev-parse", "--verify", "--end-of-options", "--help"),
            ReadOnlyGitCommand.resolveTree(NonBlankString("--help")).processBuilder().command(),
        )
        assertEquals(
            listOf("git", "cat-file", "blob", "--end-of-options", "--batch"),
            ReadOnlyGitCommand.blob("--batch").processBuilder().command(),
        )
    }
}
