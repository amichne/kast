package io.github.amichne.kast.cli.installation

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstallationWorkflowTest {
    @Test
    fun `owned legacy activation lock is narrowed before installation proceeds`(
        @TempDir temporary: Path,
    ) {
        val lock = Files.writeString(temporary.resolve("activation.lock"), "")
        Files.setPosixFilePermissions(lock, PosixFilePermissions.fromString("rw-r--r--"))

        assertTrue(secureActivationLock(lock))
        assertEquals(
            PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(lock),
        )
    }
}
