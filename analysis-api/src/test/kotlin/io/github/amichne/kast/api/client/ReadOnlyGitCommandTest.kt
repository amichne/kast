package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.contract.NonBlankString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    @Test
    fun `inherited repository selectors cannot escape the workspace boundary`() {
        val environment = ReadOnlyGitProcessEnvironment.fromInherited(
            mapOf(
                "PATH" to "/usr/bin",
                "GIT_DIR" to "/outside/.git",
                "GIT_WORK_TREE" to "/outside",
                "GIT_COMMON_DIR" to "/outside/common",
                "GIT_INDEX_FILE" to "/outside/index",
                "GIT_OBJECT_DIRECTORY" to "/outside/objects",
                "GIT_ALTERNATE_OBJECT_DIRECTORIES" to "/outside/alternate",
                "GIT_CEILING_DIRECTORIES" to "/outside/ceiling",
                "GIT_DISCOVERY_ACROSS_FILESYSTEM" to "1",
                "GIT_OPTIONAL_LOCKS" to "1",
            ),
        )

        val process = ReadOnlyGitCommand.workspaceTopLevel().processBuilder(environment)

        assertEquals("/usr/bin", process.environment()["PATH"])
        assertEquals("0", process.environment()["GIT_OPTIONAL_LOCKS"])
        assertFalse(
            process.environment().keys.any {
                it in setOf(
                    "GIT_DIR",
                    "GIT_WORK_TREE",
                    "GIT_COMMON_DIR",
                    "GIT_INDEX_FILE",
                    "GIT_OBJECT_DIRECTORY",
                    "GIT_ALTERNATE_OBJECT_DIRECTORIES",
                    "GIT_CEILING_DIRECTORIES",
                    "GIT_DISCOVERY_ACROSS_FILESYSTEM",
                )
            },
        )
    }
}
