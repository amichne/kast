package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

internal class ConnectionDestinationTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @EnumSource(HarnessConnection::class)
    fun `every connection accepts a selected destination`(connection: HarnessConnection) {
        val arguments =
            listOf("connect") +
                connection.publicName.split(' ') +
                listOf("--destination", temporary.resolve("chosen").toString())
        assertTrue(parseManagementCommand(arguments) is ManagementParsing.Selected)
    }

    @ParameterizedTest
    @EnumSource(HarnessConnection::class, names = ["PI", "COPILOT", "CODEX_APP_SERVER"])
    fun `default connect recovers the selected slot and retains its private backup`(connection: HarnessConnection) {
        val (root, home) = integrationFixture(temporary, connection)
        val target = registrationDestinationFor(root, home, connection)
        Files.createDirectories(target.parent)
        Files.writeString(target, "previous connection")
        val sibling = target.resolveSibling("unrelated")
        Files.writeString(sibling, "preserve")
        assertFalse(connectHarness(root, home, connection))
        assertEquals("preserve", Files.readString(sibling))
        val backup =
            Files.list(target.parent).use { paths ->
                paths.filter { it.toString().endsWith(".prior") }.toList().single()
            }
        assertEquals("previous connection", Files.readString(backup))
        assertEquals(
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            ),
            Files.getPosixFilePermissions(backup),
        )
        assertTrue(connectHarness(root, home, connection))
        assertTrue(disconnectHarness(root, home, connection.harness))
        assertFalse(Files.exists(target))
        assertEquals("previous connection", Files.readString(backup))
    }
}
