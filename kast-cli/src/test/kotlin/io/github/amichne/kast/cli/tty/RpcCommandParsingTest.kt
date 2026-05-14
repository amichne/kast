package io.github.amichne.kast.cli.tty

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class RpcCommandParsingTest {
    private val parser = CliCommandParser(defaultCliJson())

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `rpc parses positional json argument`() {
        val json = """{"jsonrpc":"2.0","method":"health","id":1}"""
        val command = parser.parse(arrayOf("rpc", json, "--workspace-root=$tempDir"))

        assertTrue(command is CliCommand.Rpc)
        val rpc = command as CliCommand.Rpc
        assertEquals(json, rpc.rawJson)
        assertEquals(tempDir, rpc.workspaceRootOverride)
    }

    @Test
    fun `rpc parses request-file option`() {
        val json = """{"jsonrpc":"2.0","method":"health","id":1}"""
        val requestFile = tempDir.resolve("request.json")
        requestFile.writeText(json)

        val command = parser.parse(arrayOf("rpc", "--request-file=$requestFile", "--workspace-root=$tempDir"))

        assertTrue(command is CliCommand.Rpc)
        val rpc = command as CliCommand.Rpc
        assertEquals(json, rpc.rawJson)
        assertEquals(tempDir, rpc.workspaceRootOverride)
    }

    @Test
    fun `rpc without argument or request-file throws CliFailure`() {
        val failure = assertThrows<CliFailure> {
            parser.parse(arrayOf("rpc", "--workspace-root=$tempDir"))
        }

        assertEquals("CLI_USAGE", failure.code)
        assertTrue(failure.message.contains("rpc requires"))
    }

    @Test
    fun `rpc without workspace-root sets null override`() {
        val json = """{"jsonrpc":"2.0","method":"health","id":1}"""
        val command = parser.parse(arrayOf("rpc", json))

        assertTrue(command is CliCommand.Rpc)
        val rpc = command as CliCommand.Rpc
        assertEquals(json, rpc.rawJson)
        assertNull(rpc.workspaceRootOverride)
    }

    @Test
    fun `up parses with workspace-root`() {
        val command = parser.parse(arrayOf("up", "--workspace-root=$tempDir"))

        assertTrue(command is CliCommand.Up)
        val up = command as CliCommand.Up
        assertEquals(tempDir, up.options.workspaceRoot.toJavaPath())
    }

    @Test
    fun `status parses with workspace-root`() {
        val command = parser.parse(arrayOf("status", "--workspace-root=$tempDir"))

        assertTrue(command is CliCommand.Status)
        val status = command as CliCommand.Status
        assertEquals(tempDir, status.options.workspaceRoot.toJavaPath())
    }

    @Test
    fun `stop parses with workspace-root`() {
        val command = parser.parse(arrayOf("stop", "--workspace-root=$tempDir"))

        assertTrue(command is CliCommand.Stop)
        val stop = command as CliCommand.Stop
        assertEquals(tempDir, stop.options.workspaceRoot.toJavaPath())
    }
}
