package io.github.amichne.kast.appserver

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LegacyLoginBootstrapTest {
    @Test
    fun `only exact private login agent bytes and ownership are admitted`(@TempDir temporary: Path) {
        val home = temporary.toRealPath()
        val agent = home.resolve("Library/LaunchAgents/kast.login.plist")
        val expected = "<!-- Kast App Server login bootstrap v1 --><plist>owned</plist>"
        assertEquals(LegacyLoginBootstrapObservation.Absent, LegacyLoginBootstrap.observe(agent, home, expected))

        Files.createDirectories(agent.parent)
        Files.writeString(agent, expected)
        Files.setPosixFilePermissions(agent, PosixFilePermissions.fromString("rw-------"))
        assertEquals(LegacyLoginBootstrapObservation.Exact, LegacyLoginBootstrap.observe(agent, home, expected))

        Files.writeString(agent, "$expected<dict>foreign</dict>")
        assertEquals(LegacyLoginBootstrapObservation.Rejected, LegacyLoginBootstrap.observe(agent, home, expected))
        Files.writeString(agent, expected)
        Files.setPosixFilePermissions(agent, PosixFilePermissions.fromString("rw-r--r--"))
        assertEquals(LegacyLoginBootstrapObservation.Rejected, LegacyLoginBootstrap.observe(agent, home, expected))

        Files.delete(agent)
        Files.createSymbolicLink(agent, home.resolve("foreign"))
        assertEquals(LegacyLoginBootstrapObservation.Rejected, LegacyLoginBootstrap.observe(agent, home, expected))
    }

    @Test
    fun `foreign launch agent directory rejects before file effects`(@TempDir temporary: Path) {
        val home = temporary.toRealPath()
        val foreign = Files.createDirectory(home.resolve("foreign"))
        val library = home.resolve("Library")
        Files.createSymbolicLink(library, foreign)
        val agent = library.resolve("LaunchAgents/kast.login.plist")
        assertEquals(LegacyLoginBootstrapObservation.Rejected, LegacyLoginBootstrap.observe(agent, home, "owned"))
    }
}
