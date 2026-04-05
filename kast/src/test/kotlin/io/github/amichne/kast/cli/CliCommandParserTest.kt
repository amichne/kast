package io.github.amichne.kast.cli

import io.github.amichne.kast.api.RefreshQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CliCommandParserTest {
    private val parser = CliCommandParser(defaultCliJson())

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `no arguments opens help`() {
        val command = parser.parse(emptyArray())

        assertEquals(CliCommand.Help(), command)
    }

    @Test
    fun `namespace arguments open contextual help`() {
        val command = parser.parse(arrayOf("workspace"))

        assertEquals(CliCommand.Help(listOf("workspace")), command)
    }

    @Test
    fun `completion namespace opens contextual help`() {
        val command = parser.parse(arrayOf("completion"))

        assertEquals(CliCommand.Help(listOf("completion")), command)
    }

    @Test
    fun `scoped help flag keeps the command topic`() {
        val command = parser.parse(arrayOf("workspace", "status", "--help"))

        assertEquals(CliCommand.Help(listOf("workspace", "status")), command)
    }

    @Test
    fun `completion bash parses to completion command`() {
        val command = parser.parse(arrayOf("completion", "bash"))

        assertEquals(CliCommand.Completion(CliCompletionShell.BASH), command)
    }

    @Test
    fun `call hierarchy parses from inline options`() {
        val command = parser.parse(
            arrayOf(
                "call",
                "hierarchy",
                "--workspace-root=$tempDir",
                "--file-path=$tempDir/Sample.kt",
                "--offset=12",
                "--direction=incoming",
                "--depth=0",
                "--max-total-calls=32",
                "--max-children-per-node=8",
                "--timeout-millis=4000",
                "--persist-to-git-sha-cache=true",
            ),
        )

        assertTrue(command is CliCommand.CallHierarchy)
        val hierarchyCommand = command as CliCommand.CallHierarchy
        assertEquals(tempDir, hierarchyCommand.options.workspaceRoot)
        assertEquals(io.github.amichne.kast.api.CallDirection.INCOMING, hierarchyCommand.query.direction)
        assertEquals(0, hierarchyCommand.query.depth)
        assertEquals(32, hierarchyCommand.query.maxTotalCalls)
        assertEquals(8, hierarchyCommand.query.maxChildrenPerNode)
        assertEquals(4000L, hierarchyCommand.query.timeoutMillis)
        assertTrue(hierarchyCommand.query.persistToGitShaCache)
    }

    @Test
    fun `workspace refresh parses from inline options`() {
        val command = parser.parse(
            arrayOf(
                "workspace",
                "refresh",
                "--workspace-root=$tempDir",
                "--file-paths=$tempDir/A.kt,$tempDir/B.kt",
            ),
        )

        assertTrue(command is CliCommand.WorkspaceRefresh)
        val refreshCommand = command as CliCommand.WorkspaceRefresh
        assertEquals(tempDir, refreshCommand.options.workspaceRoot)
        assertEquals(
            RefreshQuery(
                filePaths = listOf(
                    tempDir.resolve("A.kt").toString(),
                    tempDir.resolve("B.kt").toString(),
                ),
            ),
            refreshCommand.query,
        )
    }

    @Test
    fun `version flag returns version command`() {
        val command = parser.parse(arrayOf("--version"))

        assertSame(CliCommand.Version, command)
    }

    @Test
    fun `usage errors include command specific help`() {
        val failure = assertThrows<CliFailure> {
            parser.parse(
                arrayOf(
                    "edits",
                    "apply",
                    "--workspace-root=$tempDir",
                ),
            )
        }

        assertEquals("CLI_USAGE", failure.code)
        assertTrue(checkNotNull(failure.details["usage"]).contains("edits apply"))
        assertTrue(checkNotNull(failure.details["help"]).contains("help edits apply"))
    }
}
