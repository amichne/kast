package io.github.amichne.kast.appserver

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Private filesystem occupancy proof; no socket connection or incumbent-process probe is permitted. */
internal class CodexControlSocketAvailabilityTest {
    @Test
    fun `absent canonical endpoint is available without creating its parent`(@TempDir temporary: Path) {
        val home = temporary.toRealPath()
        assertEquals(CodexControlSocketAvailability.AVAILABLE, CodexControlSocketAvailability.observe(home))
        assertTrue(Files.notExists(home.resolve("app-server-control"), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `occupied canonical endpoint is observed without following or removing a dangling link`(
        @TempDir temporary: Path
    ) {
        val home = temporary.toRealPath()
        val socket = Files.createDirectory(home.resolve("app-server-control")).resolve("app-server-control.sock")
        val target = home.resolve("absent-target")
        Files.createSymbolicLink(socket, target)
        assertEquals(CodexControlSocketAvailability.OCCUPIED, CodexControlSocketAvailability.observe(home))
        assertEquals(target, Files.readSymbolicLink(socket))
        assertTrue(Files.notExists(target, LinkOption.NOFOLLOW_LINKS))
    }
}
