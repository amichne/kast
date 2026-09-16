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
    @Test
    fun `receipted current alias loads the same configuration as its immutable path`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val installation = root.resolve("versions/1.2.3-" + "a".repeat(64))
        val configuration = Files.createDirectories(installation.resolve("config")).resolve("environment")
        Files.writeString(configuration, "KAST_INDEXER_MAX_HEAP=8g\n")
        val current = Files.createSymbolicLink(root.resolve("current"), root.relativize(installation))
        Files.writeString(root.resolve("activation.lock"), "")
        writeAliasManifest(installation, current, configuration, root)
        val alias =
            InstalledSavedConfigurationIngress.read(current.resolve("config/environment").toString(), emptyMap())
        val pinned = InstalledSavedConfigurationIngress.read(configuration.toString(), emptyMap())
        assertTrue(alias is SavedConfigurationIngress.Loaded)
        assertEquals(
            (pinned as SavedConfigurationIngress.Loaded).sources.savedInstallation,
            (alias as SavedConfigurationIngress.Loaded).sources.savedInstallation,
        )
        FileChannel.open(root.resolve("activation.lock"), WRITE).use { channel ->
            channel.lock().use {
                assertTrue(
                    InstalledSavedConfigurationIngress.read(
                        current.resolve("config/environment").toString(),
                        emptyMap(),
                    ) is SavedConfigurationIngress.Rejected
                )
            }
        }
        val changed =
            InstalledConfigurationAlias.read(current.resolve("config/environment"), emptyMap()) { pinnedPath, selected
                ->
                val loaded = InstalledSavedConfigurationIngress.read(pinnedPath.toString(), selected)
                Files.delete(current)
                Files.createSymbolicLink(current, Path.of("versions/other"))
                loaded
            }
        assertEquals("CHANGED_DURING_READ", (changed as SavedConfigurationIngress.Rejected).rejection.reason())
        Files.delete(current)
        Files.createSymbolicLink(current, root.relativize(installation))
        Files.delete(installation.resolve("installation.json"))
        assertTrue(
            InstalledSavedConfigurationIngress.read(current.resolve("config/environment").toString(), emptyMap())
                is SavedConfigurationIngress.Rejected
        )
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
        Files.writeString(liveLookingConfig, "KAST_INDEXER_MAX_HEAP=secret-invalid-input\n")
        val loaded =
            InstalledSavedConfigurationIngress.load(mapOf("HOME" to home.toString()))
                as SavedConfigurationIngress.Loaded
        assertEquals(SavedConfigurationObservation.ABSENT, loaded.observation)
        val resolved = ResolvedKastConfiguration.resolve(loaded.sources) as Refinement.Refined
        assertEquals(
            ConfigurationSource.DEFAULT,
            resolved.value.inspection().single { it.key == "KAST_INDEXER_MAX_HEAP" }.source,
        )
    }

    @Test
    fun `explicit saved selector rejects symlink and missing files`(@TempDir temporary: Path) {
        val directory = temporary.toRealPath()
        val target = Files.writeString(directory.resolve("real"), "KAST_INDEXER_MAX_HEAP=8g\n")
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
        val target = Files.writeString(directory.resolve("environment"), "KAST_INDEXER_MAX_HEAP=\$(touch $marker)\n")
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
