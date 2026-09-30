package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ForcedRegistrationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `forced takeover replaces only the selected adapter and rolls back receipt failure`() {
        val (root, home) = integrationFixture(temporary, HarnessConnection.PI)
        val target = home.resolve(".pi/agent/extensions/kast.ts")
        Files.createDirectories(target.parent)
        Files.writeString(target, "foreign adapter")
        val sibling = target.resolveSibling("other.ts")
        Files.writeString(sibling, "unrelated")
        val receipt = Files.readAllBytes(receiptPath(root))
        org.junit.jupiter.api.assertThrows<ManagementRejected> { connectHarness(root, home, HarnessConnection.PI) }
        assertEquals("foreign adapter", Files.readString(target))
        assertFalse(connectHarness(root, home, HarnessConnection.PI, RegistrationOwnership.REPLACE_SELECTED_SLOT))
        assertEquals("release adapter", Files.readString(target))
        assertEquals("unrelated", Files.readString(sibling))
        assertTrue(connectHarness(root, home, HarnessConnection.PI))
        Files.writeString(target, "another foreign adapter")
        Files.write(receiptPath(root), receipt)
        org.junit.jupiter.api.assertThrows<ManagementRejected> {
            connectHarness(root, home, HarnessConnection.PI, RegistrationOwnership.REPLACE_SELECTED_SLOT) { _, _ ->
                throw java.io.IOException("injected receipt commit failure")
            }
        }
        assertEquals("another foreign adapter", Files.readString(target))
        org.junit.jupiter.api.Assertions.assertArrayEquals(receipt, Files.readAllBytes(receiptPath(root)))
        assertEquals("unrelated", Files.readString(sibling))
    }

    @Suppress("LongMethod") // One transaction with exact preimage and scripted process assertions.
    @Test
    fun `forced Codex takeover restores full config and receipt after partial receipt write`() {
        val (root, home) = integrationFixture(temporary, HarnessConnection.CODEX_MCP)
        val codexHome = home.resolve(".codex")
        Files.createDirectories(codexHome)
        val config = codexHome.resolve("config.toml")
        val original =
            """
            model = "unrelated"
            [mcp_servers.kast]
            command = "foreign"
            args = ["--legacy"]
            enabled = false
            startup_timeout_sec = 45
            [mcp_servers.kast.env]
            SECRET = "preserve verbatim"
            [mcp_servers.other]
            command = "keep"
            """
                .trimIndent()
        Files.writeString(config, original)
        val receipt = Files.readAllBytes(receiptPath(root))
        var calls = 0
        var command = "foreign"
        val execute: (List<String>) -> ProcessObservation = { arguments ->
            calls++
            when (calls) {
                1,
                3 -> {
                    assertEquals(listOf("codex", "mcp", "list", "--json"), arguments)
                    ProcessObservation.Exited(
                        0,
                        Json.encodeToString(listOf(TestCodexServer("kast", TestCodexTransport("stdio", command)))),
                    )
                }
                2 -> {
                    assertEquals(
                        listOf(
                            "codex",
                            "mcp",
                            "add",
                            "kast",
                            "--",
                            root.resolve("current/bin/kast-mcp-complete").toString(),
                        ),
                        arguments,
                    )
                    command = arguments.last()
                    Files.writeString(config, "replacement config")
                    ProcessObservation.Exited(0, "")
                }
                else -> throw AssertionError("unexpected Codex invocation")
            }
        }
        org.junit.jupiter.api.assertThrows<ManagementRejected> {
            connectHarness(
                root,
                home,
                HarnessConnection.CODEX_MCP,
                RegistrationOwnership.REPLACE_SELECTED_SLOT,
                codexHome,
                execute,
            ) { _, _ ->
                Files.writeString(receiptPath(root), "partial receipt")
                throw java.io.IOException("injected receipt failure")
            }
        }
        assertEquals(3, calls)
        assertEquals(original, Files.readString(config))
        org.junit.jupiter.api.Assertions.assertArrayEquals(receipt, Files.readAllBytes(receiptPath(root)))
    }

    @Test
    fun `force rejects symlink slots and retains backup on failed rollback`() {
        val (root, home) = integrationFixture(temporary, HarnessConnection.PI)
        val target = home.resolve(".pi/agent/extensions/kast.ts")
        Files.createDirectories(target.parent)
        val outside = temporary.resolve("outside.ts")
        Files.writeString(outside, "protected")
        Files.createSymbolicLink(target, outside)
        org.junit.jupiter.api.assertThrows<ManagementRejected> {
            connectHarness(root, home, HarnessConnection.PI, RegistrationOwnership.REPLACE_SELECTED_SLOT)
        }
        assertEquals("protected", Files.readString(outside))
        Files.delete(target)
        Files.writeString(target, "foreign adapter")
        val failure =
            org.junit.jupiter.api.assertThrows<ManagementRejected> {
                connectHarness(root, home, HarnessConnection.PI, RegistrationOwnership.REPLACE_SELECTED_SLOT) { _, _ ->
                    Files.delete(target)
                    Files.createDirectory(target)
                    Files.writeString(target.resolve("concurrent"), "protected")
                    throw java.io.IOException("injected replacement interference")
                }
            }
        assertEquals("connect-recovery", failure.stage)
        val backups =
            Files.list(target.parent).use { entries -> entries.filter { it.toString().endsWith(".prior") }.toList() }
        assertEquals(1, backups.size)
        assertEquals("foreign adapter", Files.readString(backups.single()))
        assertTrue(failure.reason.contains(backups.single().toString()))
        assertEquals("protected", Files.readString(target.resolve("concurrent")))
    }

    @Test
    fun `failed receipt does not overwrite a concurrent registration change`() {
        val (root, home) = integrationFixture(temporary, HarnessConnection.PI)
        val target = home.resolve(".pi/agent/extensions/kast.ts")
        Files.createDirectories(target.parent)
        Files.writeString(target, "original")
        val failure =
            org.junit.jupiter.api.assertThrows<ManagementRejected> {
                connectHarness(root, home, HarnessConnection.PI, RegistrationOwnership.REPLACE_SELECTED_SLOT) { _, _ ->
                    Files.writeString(target, "concurrent owner")
                    throw java.io.IOException("receipt failed")
                }
            }
        assertEquals("connect-recovery", failure.stage)
        assertEquals("concurrent owner", Files.readString(target))
        val backup =
            Files.list(target.parent).use { it.filter { path -> path.toString().endsWith(".prior") }.toList().single() }
        assertEquals("original", Files.readString(backup))
    }

    @Test
    fun `force requires one selected harness and retains ordinary idempotence`() {
        assertEquals(
            ManagementParsing.Selected(
                ManagementCommand.Connect(HarnessConnection.PI, RegistrationOwnership.REPLACE_SELECTED_SLOT)
            ),
            parseManagementCommand(listOf("connect", "pi", "--force")),
        )
        assertTrue((parseManagementCommand(listOf("connect", "--force")) as ManagementParsing.Print).error)
    }
}

@Serializable private data class TestCodexServer(val name: String, val transport: TestCodexTransport)

@Serializable private data class TestCodexTransport(val type: String, val command: String)
