package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RetainedInstalledProductRuntimeTest {
    @Test
    fun `retained installation admission rejects changed runtime bytes with the same size and mtime`(@TempDir temporary: Path) {
        val fixture = productFixture(temporary)
        val receipt = fixture.runtime.root.resolve("runtime.json")
        Files.setPosixFilePermissions(receipt, PosixFilePermissions.fromString("rw-------"))
        val selected =
            (InstalledProductRuntime.read(fixture.installation, fixture.runtime.manifest.platform)
                    as Refinement.Refined)
                .value
        assertEquals(Refinement.Refined(Unit), selected.admitCurrent())

        val original = Files.readString(receipt)
        val time = Files.getLastModifiedTime(receipt)
        Files.writeString(receipt, original.replaceFirst("archiveSha256", "ArchiveSha256"))
        Files.setLastModifiedTime(receipt, time)

        assertEquals(Refinement.Rejected(ContainerFailure.RUNTIME_MISMATCH), selected.admitCurrent())
    }
}
