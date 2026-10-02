package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ManagementPublicationPathTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `a parent alias cannot redirect public executable publication`() {
        val owned = temporary.toRealPath()
        val home = Files.createDirectories(owned.resolve("home"))
        val root = Files.createDirectories(home.resolve(".local/share/kast"))
        val protected = Files.createDirectories(owned.resolve("protected/bin"))
        Files.writeString(protected.resolve("keep"), "protected")
        Files.createSymbolicLink(home.resolve("commands"), protected.parent)

        val rejected =
            assertThrows<ManagementRejected> {
                preflightPublicExecutable(
                    root,
                    mapOf("HOME" to home.toString(), "XDG_CONFIG_HOME" to home.resolve("commands/bin").toString()),
                )
            }

        assertEquals("path-preflight", rejected.stage)
        assertEquals("destination parent must be physical", rejected.reason)
        assertEquals("protected", Files.readString(protected.resolve("keep")))
        assertFalse(Files.exists(protected.resolve("kast")))
    }
}
