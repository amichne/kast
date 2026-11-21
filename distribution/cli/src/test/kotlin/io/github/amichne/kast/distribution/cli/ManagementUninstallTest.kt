package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ManagementUninstallTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `replacement root cannot inherit the retired installation cleanup proof`() {
        val fixture = retiredUninstallFixture(temporary)
        Files.move(fixture.root, fixture.root.resolveSibling("protected-original"))
        Files.createDirectory(fixture.root)
        val foreign = Files.writeString(fixture.root.resolve("management.json"), "protected foreign receipt")
        assertThrows<ManagementRejected> { uninstallInstallation(fixture.root, fixture.home) }
        assertEquals("protected foreign receipt", Files.readString(foreign))
        assertTrue(Files.exists(fixture.executable))
        assertTrue(Files.exists(uninstallJournalPath(fixture.root)))
    }
}
