package io.github.amichne.kast.cli.direct

import io.github.amichne.kast.distribution.contract.InstallationResetRequest
import io.github.amichne.kast.distribution.contract.InstallationResetStorage
import io.github.amichne.kast.distribution.contract.installationResetFence
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstalledToolAdmissionTest {
    @Test
    fun `replacement storage fences tools until its final directory disappears`(@TempDir temporary: Path) {
        val root = temporary.resolve("kast")
        val installation = root.resolve("installation")
        val journal = root.resolve("recovery/replacement")
        Files.createDirectories(journal)
        assertEquals(InstalledToolAdmission.STOPPED, observeInstalledToolAdmission(installation))
        val receipt = journal.resolve("receipt.json")
        Files.writeString(receipt, "corrupt replacement evidence")
        assertEquals(InstalledToolAdmission.STOPPED, observeInstalledToolAdmission(installation))
        Files.delete(receipt)
        assertEquals(InstalledToolAdmission.STOPPED, observeInstalledToolAdmission(installation))
        Files.delete(journal)
        assertEquals(InstalledToolAdmission.AVAILABLE, observeInstalledToolAdmission(installation))
    }

    @Test
    fun `replacement marker keeps its exact installation scope`(@TempDir temporary: Path) {
        val root = temporary.resolve("kast")
        Files.createDirectories(root.resolve("recovery"))
        val journal = root.resolve("recovery/replacement")
        Files.createSymbolicLink(journal, temporary.resolve("absent"))
        assertEquals(InstalledToolAdmission.STOPPED, observeInstalledToolAdmission(root.resolve("installation")))
        assertEquals(
            InstalledToolAdmission.AVAILABLE,
            observeInstalledToolAdmission(temporary.resolve("other/installation")),
        )
        Files.delete(journal)
        assertEquals(InstalledToolAdmission.AVAILABLE, observeInstalledToolAdmission(root.resolve("installation")))
    }

    @Test
    fun `exterior reset marker blocks tools while the entire installation is absent`(@TempDir temporary: Path) {
        val root = temporary.resolve("kast")
        val installation = root.resolve("installation")
        assertEquals(InstalledToolAdmission.AVAILABLE, observeInstalledToolAdmission(installation))
        val marker = installationResetFence(root)
        Files.writeString(marker, "malformed journal still fences")
        assertEquals(InstalledToolAdmission.STOPPED, observeInstalledToolAdmission(installation))
        Files.writeString(
            marker,
            Json.encodeToString(InstallationResetRequest(installation.toString(), InstallationResetStorage.Activating)),
        )
        assertEquals(InstalledToolAdmission.STOPPED, observeInstalledToolAdmission(installation))
        Files.delete(marker)
        assertEquals(InstalledToolAdmission.AVAILABLE, observeInstalledToolAdmission(installation))
    }
}
