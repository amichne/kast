package io.github.amichne.kast.cli

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
    fun `scoped help flag keeps the command topic`() {
        val command = parser.parse(arrayOf("workspace", "status", "--help"))

        assertEquals(CliCommand.Help(listOf("workspace", "status")), command)
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
