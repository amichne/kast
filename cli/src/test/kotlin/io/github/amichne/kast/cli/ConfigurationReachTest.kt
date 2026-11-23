package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.direct.ClientInstallationFailure
import io.github.amichne.kast.cli.direct.InstalledClientContext
import io.github.amichne.kast.cli.installation.InstallationManifest
import io.github.amichne.kast.cli.installation.PayloadFile
import io.github.amichne.kast.cli.installation.installationManifestJson
import io.github.amichne.kast.distribution.contract.InstallationLayout
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Each `:cli` entry that selects the installation configuration reaches the file that [InstallationLayout.configuration]
 * names. Stage 8 moves that file from the version root to the management root by changing the layout alone, so an
 * entry that derived the file itself would fail here after that change.
 */
class ConfigurationReachTest {
    private val layout = InstallationLayout.of(Path.of("/owned/home/.local/share/kast/installation"))
    private val other = InstallationLayout.of(Path.of("/owned/other/.local/share/kast/installation"))

    @Test
    fun `service control defaults the selector to the layout's configuration file`() {
        assertEquals(
            layout.configuration.environment.toString(),
            serviceControlEnvironment(layout.payload.kast, emptyMap()).getValue(CONFIGURATION_FILE),
        )
    }

    @Test
    fun `managed headless shutdown accepts only the layout's own configuration file`() {
        assertTrue(selectsOwnConfiguration(layout, null))
        assertTrue(selectsOwnConfiguration(layout, layout.configuration.environment.toString()))
        assertFalse(selectsOwnConfiguration(layout, other.configuration.environment.toString()))
    }

    @Test
    fun `the query client forwards only the layout's configuration file`(@TempDir temporary: Path) {
        val installation = Files.createDirectories(temporary.toRealPath().resolve("installation"))
        val owned = InstallationLayout.of(installation)
        val payload = payload(owned)

        record(owned, payload, owned.configuration.environment)
        val admitted = InstalledClientContext.admit(payload, emptyMap()) as Refinement.Refined
        assertEquals(owned.configuration.environment.toString(), admitted.value.environment[CONFIGURATION_FILE])

        record(owned, payload, other.configuration.environment)
        assertEquals(
            Refinement.Rejected(ClientInstallationFailure.MANIFEST),
            InstalledClientContext.admit(payload, emptyMap()),
        )
    }

    private fun payload(layout: InstallationLayout): Path {
        val payload = InstallationLayout.Relative.QUERY_EXECUTABLE.under(layout.version.path)
        Files.createDirectories(payload.parent)
        Files.writeString(payload, "qualified fixture bytes")
        return payload
    }

    private fun record(layout: InstallationLayout, payload: Path, configuration: Path) {
        val digest =
            "sha256:" +
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(payload)).joinToString("") {
                    "%02x".format(it)
                }
        Files.writeString(
            layout.payload.manifest,
            installationManifestJson.encodeToString(
                InstallationManifest(
                    semanticVersion = "1.2.3",
                    installationRoot = layout.version.path.toString(),
                    payloadIdentity = digest,
                    controlSha256 = digest,
                    codexHome = layout.version.path.resolveSibling("codex").toString(),
                    configuration = configuration.toString(),
                    workspaceRegistry = layout.configuration.workspaceRegistry.toString(),
                    stateRoot = layout.state.directory.toString(),
                    externalAnchors = emptyList(),
                    payloadFiles =
                        listOf(PayloadFile(InstallationLayout.Relative.QUERY_EXECUTABLE.value, digest, 493)),
                )
            ),
        )
    }

    private companion object {
        const val CONFIGURATION_FILE = "KAST_CONFIGURATION_FILE"
    }
}
