package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.distribution.managed.endpoint.InstalledUpstreamDirectories
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PriorInstallationTransportResetTest {
    @Test
    fun `transport reset excludes the physical run directory instead of subtracting its path components`() {
        val temporary = Files.createTempDirectory(Path.of("/tmp"), "kr-").toRealPath()
        try {
            val home = Files.createDirectory(temporary.resolve("h"))
            val installation = temporary.resolve("k")
            val run = Files.createDirectories(installation.resolve("state/run"))
            val protected = Files.writeString(run.resolve("keep"), "keep")
            assertEquals(
                run,
                InstalledUpstreamDirectories.transportPath(run.resolve("u.sock")).parent,
                "fixture must keep the upstream in the physical run directory",
            )
            assertEquals(PriorInstallationPreparation.PREPARED, resetInstallationTransport(installation, home))
            assertEquals("keep", Files.readString(protected))
        } finally {
            Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
