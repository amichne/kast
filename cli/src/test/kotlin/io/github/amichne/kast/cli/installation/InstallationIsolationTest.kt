package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.distribution.contract.configuration.BrokerPublicEndpointMode
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

internal class InstallationIsolationTest {
    @Test
    fun `ordinary installation commits without a service enable effect`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val home = Files.createDirectory(root.resolve("home"))
        val request =
            releaseRequest(
                root,
                home.resolve(".local/share/kast"),
                home.resolve(".local/bin"),
                home,
                Files.createDirectory(root.resolve("codex")),
                "1.2.3",
            )
        val service = request.controlRoot.value.resolve("share/kast/libexec/kast-service")
        Files.createDirectories(service.parent)
        Files.writeString(service, "#!/bin/sh\nprintf '%s\\n' \"\$@\" > \"\$HOME/service-called\"\nexit 17\n")
        service.toFile().setExecutable(true)

        val outcome =
            assertInstanceOf(
                InstallationOutcome.Complete::class.java,
                executeFixtureInstallation(request, InstallationActivationPolicy.ACTIVATE),
            )

        assertEquals(InstallationActivation.NotRequested, outcome.report.activation)
        assertEquals(InstallationReportStatus.INSTALLED, outcome.report.status)
        assertTrue(Files.notExists(home.resolve("service-called")))
        assertTrue(Files.isRegularFile(request.installRoot.value.resolve("installation/installation.json")))
    }

    @Test
    fun `explicit shared endpoint rejects before installation effects`() {
        assertEquals(
            Refinement.Rejected(InstallationRequestFailure.InvalidValue(InstallationEnvironment.PUBLIC_ENDPOINT)),
            InstallationPublicEndpointSelection.admit("codex-control"),
        )
    }

    @Test
    fun `saved shared endpoint migrates to private with finite provenance`(@TempDir temporary: Path) {
        val prior = Files.createDirectory(temporary.toRealPath().resolve("prior"))
        val configuration = Files.createDirectory(prior.resolve("config")).resolve("environment")
        Files.writeString(configuration, "KAST_APP_SERVER_PUBLIC_ENDPOINT=codex-control\nKAST_DEBUG=1\n")

        val selected =
            when (
                val outcome = selectInstallationConfiguration(InstallationPublicEndpointSelection.Unspecified, prior)
            ) {
                is Refinement.Refined -> outcome.value
                is Refinement.Rejected -> error("fixture configuration rejected: ${outcome.failure}")
            }

        assertEquals(BrokerPublicEndpointMode.PRIVATE, selected.endpoint)
        assertEquals("LEGACY_SHARED_ENDPOINT_MIGRATED", selected.reason.name)
        assertEquals("KAST_APP_SERVER_PUBLIC_ENDPOINT=codex-control\nKAST_DEBUG=1\n", Files.readString(configuration))
    }
}
