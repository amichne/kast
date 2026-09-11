package io.github.amichne.kast.distribution.managed.endpoint

import io.github.amichne.kast.kernel.Validation
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstalledUpstreamDirectoryTest {
    @Test
    fun `semantic directory types reject same-shaped paths from the wrong namespace`(@TempDir temporary: Path) {
        val run = Files.createDirectories(temporary.toRealPath().resolve("state/run"))
        val unrelatedPrivateDirectory = Files.createDirectory(temporary.resolve("kast-codex-${"a".repeat(32)}"))
        Files.setPosixFilePermissions(run, PosixFilePermissions.fromString("rwx------"))
        Files.setPosixFilePermissions(unrelatedPrivateDirectory, PosixFilePermissions.fromString("rwx------"))

        assertTrue(InstalledPhysicalRunDirectory.capture(run) is Validation.Validated)
        assertTrue(InstalledPrivateUpstreamDirectory.capture(unrelatedPrivateDirectory) is Validation.Rejected)
    }
}
