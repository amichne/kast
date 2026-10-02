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
