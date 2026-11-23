package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.direct.ClientInstallationFailure
import io.github.amichne.kast.cli.direct.InstalledClientContext
import io.github.amichne.kast.cli.installation.InstallationManifest
import io.github.amichne.kast.cli.installation.ManagedHeadlessRoot
import io.github.amichne.kast.cli.installation.PayloadFile
import io.github.amichne.kast.cli.installation.installationManifestJson
import io.github.amichne.kast.distribution.contract.InstallationLayout
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Each `:cli` entry that defaults to the installation configuration reaches the file that
 * [InstallationLayout.configuration] names. Stage 8 moves that file from the version root to the management root by
 * changing the layout alone, so an entry that derived the file itself would fail here after that change. An explicit
 * selector is not an installation path: inside a private workspace it names the container profile.
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
    fun `managed shutdown reads the Host that a container profile's configuration file selects`() {
        val container = "/owned/home/.local/share/kast-workspaces/containers/${"a".repeat(64)}"
        for (profile in listOf(container, "$container/generations/b2088245-73e9-4603-9939-ce4556f8e5f9")) assertEquals(
            "$profile/config/selected-ide.json",
            selectedIde("$profile/config/environment"),
        )
        // The root is the file's grandparent whatever it names; the admission that follows decides what is valid.
        assertEquals(
            "/owned/other/.local/share/kast/installation/config/selected-ide.json",
            selectedIde("/owned/other/.local/share/kast/installation/config/environment"),
        )
        assertEquals("relative/config/selected-ide.json", selectedIde("relative/config/environment"))
    }

    @Test
    fun `managed shutdown without a container profile reads the executable's installation`() {
        val installed = "/owned/home/.local/share/kast/installation/config/selected-ide.json"
        assertEquals(installed, selectedIde(null))
        assertEquals(installed, selectedIde("/owned/home/.local/share/kast/installation/config/environment"))
        assertEquals(
            ManagedHeadlessRoot.Installation(layout),
            ManagedHeadlessRoot.select(layout, "/owned/home/.local/share/kast/installation/config/environment"),
        )
        for (shallow in listOf("/environment", "config/environment")) assertEquals(installed, selectedIde(shallow))
    }

    @Test
    fun `managed shutdown keeps the uncaught outcome of a selector that has no parent or is no path`() {
        for (selector in listOf("", "environment", "/")) assertThrows(
            NullPointerException::class.java,
            { selectedIde(selector) },
            selector,
        )
        assertThrows(InvalidPathException::class.java) { selectedIde("invalid\u0000path") }
    }

    private fun selectedIde(selector: String?): String =
        ManagedHeadlessRoot.select(layout, selector).selectedIde.toString()

    @Test
    fun `the query client forwards an explicit selector unchanged`(@TempDir temporary: Path) {
        val owned = InstallationLayout.of(Files.createDirectories(temporary.toRealPath().resolve("installation")))
        val payload = payload(owned)
        record(owned, payload, owned.configuration.environment)
        val profile = "/owned/home/.local/share/kast-workspaces/containers/${"a".repeat(64)}/config/environment"

        val admitted = InstalledClientContext.admit(payload, mapOf(CONFIGURATION_FILE to profile)) as Refinement.Refined

        assertEquals(profile, admitted.value.environment[CONFIGURATION_FILE])
    }

    @Test
    fun `the query client defaults only to the layout's configuration file`(@TempDir temporary: Path) {
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
                    payloadFiles = listOf(PayloadFile(InstallationLayout.Relative.QUERY_EXECUTABLE.value, digest, 493)),
                )
            ),
        )
    }

    private companion object {
        const val CONFIGURATION_FILE = "KAST_CONFIGURATION_FILE"
    }
}
