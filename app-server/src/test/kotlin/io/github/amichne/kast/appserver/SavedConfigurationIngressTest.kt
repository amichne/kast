package io.github.amichne.kast.appserver

import io.github.amichne.kast.distribution.contract.configuration.ConfigurationSource
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.kernel.Refinement
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SavedConfigurationIngressTest {
    // Budget: one private root with only the selected file/link, manifest and activation lock.
    // No product, IDE or child process. Gradle/JUnit provisioning is outside this behavior budget.
    @Test
    fun `receipted current alias loads the same configuration as its immutable path`(@TempDir temporary: Path) {
        val fixture = aliasFixture(temporary)
        val alias =
            InstalledSavedConfigurationIngress.read(fixture.selected.toString(), emptyMap())
                as SavedConfigurationIngress.Loaded
        val pinned =
            InstalledSavedConfigurationIngress.read(fixture.configuration.toString(), emptyMap())
                as SavedConfigurationIngress.Loaded
        assertEquals(listOf("KAST_READ_HOST_REFERENCE_ENTRIES" to "8192"), alias.sources.savedInstallation)
        assertEquals(pinned.sources.savedInstallation, alias.sources.savedInstallation)
    }

    @Test
    fun `current alias rejects a held activation lock`(@TempDir temporary: Path) {
        val fixture = aliasFixture(temporary)
        FileChannel.open(fixture.lock, WRITE).use { channel ->
            channel.lock().use {
                val result =
                    InstalledSavedConfigurationIngress.read(fixture.selected.toString(), emptyMap())
                        as SavedConfigurationIngress.Rejected
                assertEquals(
                    SavedConfigurationIngressFailure.INVALID_INSTALLATION,
                    (result.rejection as SavedConfigurationIngressRejection.File).failure,
                )
            }
        }
    }

    @Test
    fun `current alias replacement during pinned read rejects deterministically`(@TempDir temporary: Path) {
        val fixture = aliasFixture(temporary)
        var reads = 0
        val changed =
            InstalledConfigurationAlias.read(fixture.selected, emptyMap()) { pinnedPath, selected ->
                reads++
                assertEquals(fixture.configuration, pinnedPath)
                assertEquals(emptyMap<String, String>(), selected)
                val loaded = InstalledSavedConfigurationIngress.read(pinnedPath.toString(), selected)
                Files.delete(fixture.current)
                Files.createSymbolicLink(fixture.current, Path.of("versions/other"))
                loaded
            } as SavedConfigurationIngress.Rejected
        assertEquals(1, reads)
        assertEquals(
            SavedConfigurationIngressFailure.CHANGED_DURING_READ,
            (changed.rejection as SavedConfigurationIngressRejection.File).failure,
        )
    }

    @Test
    fun `current alias rejects a missing ownership manifest`(@TempDir temporary: Path) {
        val fixture = aliasFixture(temporary)
        Files.delete(fixture.configuration.parent.parent.resolve("installation.json"))
        val result =
            InstalledSavedConfigurationIngress.read(fixture.selected.toString(), emptyMap())
                as SavedConfigurationIngress.Rejected
        assertEquals(
            SavedConfigurationIngressFailure.INVALID_INSTALLATION,
            (result.rejection as SavedConfigurationIngressRejection.File).failure,
        )
    }

    private data class AliasFixture(val current: Path, val configuration: Path, val lock: Path) {
        val selected: Path = current.resolve("config/environment")
    }

    private fun aliasFixture(temporary: Path): AliasFixture {
        val root = temporary.toRealPath()
        val installation = root.resolve("versions/1.2.3-" + "a".repeat(64))
        val configuration = Files.createDirectories(installation.resolve("config")).resolve("environment")
        Files.writeString(configuration, "KAST_READ_HOST_REFERENCE_ENTRIES=8192\n")
        val current = Files.createSymbolicLink(root.resolve("current"), root.relativize(installation))
        val lock = Files.writeString(root.resolve("activation.lock"), "")
        writeAliasManifest(installation, current, configuration, root)
        return AliasFixture(current, configuration, lock)
    }

    private fun writeAliasManifest(installation: Path, current: Path, configuration: Path, root: Path) {
        Files.writeString(
            installation.resolve("installation.json"),
            Json.encodeToString(
                AliasManifestFixture(
                    2,
                    "1.2.3",
                    installation.toString(),
                    "sha256:" + "a".repeat(64),
                    configuration.toString(),
                    listOf(AliasAnchorFixture("current", current.toString(), root.relativize(installation).toString())),
                )
            ),
        )
    }

    @Test
    fun `unselected home configuration is never read`(@TempDir temporary: Path) {
        val home = temporary.toRealPath()
        val liveLookingConfig = Files.createDirectories(home.resolve(".kast/config")).resolve("environment")
        Files.writeString(liveLookingConfig, "KAST_READ_HOST_REFERENCE_ENTRIES=secret-invalid-input\n")
        val loaded =
            InstalledSavedConfigurationIngress.load(mapOf("HOME" to home.toString()))
                as SavedConfigurationIngress.Loaded
        assertEquals(SavedConfigurationObservation.ABSENT, loaded.observation)
        val resolved = ResolvedKastConfiguration.resolve(loaded.sources) as Refinement.Refined
        assertEquals(
            ConfigurationSource.DEFAULT,
            resolved.value.inspection().single { it.key == "KAST_READ_HOST_REFERENCE_ENTRIES" }.source,
        )
    }

    @Test
    fun `explicit saved selector rejects symlink and missing files`(@TempDir temporary: Path) {
        val directory = temporary.toRealPath()
        val target = Files.writeString(directory.resolve("real"), "KAST_READ_HOST_REFERENCE_ENTRIES=8192\n")
        val alias = Files.createSymbolicLink(directory.resolve("alias"), target)
        val symbolic =
            InstalledSavedConfigurationIngress.read(alias.toString(), emptyMap()) as SavedConfigurationIngress.Rejected
        assertEquals("NOT_REGULAR", symbolic.rejection.reason())
        val missing =
            InstalledSavedConfigurationIngress.read(directory.resolve("missing").toString(), emptyMap())
                as SavedConfigurationIngress.Rejected
        assertEquals("UNAVAILABLE", missing.rejection.reason())
        assertFalse(Files.exists(directory.resolve("missing")))
    }

    @Test
    fun `workspace overlay cannot follow a symlinked configuration ancestor`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val workspace = Files.createDirectory(root.resolve("workspace"))
        val outside = Files.createDirectory(root.resolve("outside"))
        Files.createSymbolicLink(root.resolve("config"), outside)
        val result =
            InstalledWorkspaceConfigurationIngress.load(root, workspace, emptyMap())
                as SavedConfigurationIngress.Rejected
        assertEquals("NOT_REGULAR", result.rejection.reason())
        assertFalse(Files.exists(outside.resolve("workspaces")))
    }

    @Test
    fun `literal shell expressions are data and never executed`(@TempDir temporary: Path) {
        val directory = temporary.toRealPath()
        val marker = directory.resolve("executed")
        val target =
            Files.writeString(directory.resolve("environment"), "KAST_READ_HOST_REFERENCE_ENTRIES=\$(touch $marker)\n")
        val loaded =
            InstalledSavedConfigurationIngress.read(target.toString(), emptyMap()) as SavedConfigurationIngress.Loaded
        assertTrue(ResolvedKastConfiguration.resolve(loaded.sources) is Refinement.Rejected)
        assertFalse(Files.exists(marker))
    }
}

@Serializable
private data class AliasManifestFixture(
    val schemaVersion: Int,
    val semanticVersion: String,
    val installationRoot: String,
    val payloadIdentity: String,
    val configuration: String,
    val externalAnchors: List<AliasAnchorFixture>,
)

@Serializable private data class AliasAnchorFixture(val kind: String, val path: String, val expectedLinkTarget: String)
