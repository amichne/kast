package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.WorkspaceConfigurationConflict
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationSource
import io.github.amichne.kast.distribution.contract.configuration.WorkspaceDependencySharing
import io.github.amichne.kast.distribution.contract.gradle.GradleImportEnvironment
import io.github.amichne.kast.distribution.managed.CanonicalRootDiscovery
import io.github.amichne.kast.distribution.managed.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SavedWorkspaceDelegationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `shared workspace reuses recorded policy on omission and rejects explicit private selection`() {
        val fixture = productFixture(temporary)
        val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        Files.writeString(workspace.resolve("settings.gradle.kts"), "")
        val root = (FilesystemCanonicalRootDiscovery.discover(workspace) as CanonicalRootDiscovery.Discovered).root
        val config = Files.createDirectories(fixture.installation.resolve("config")).resolve("environment")
        val key = "KAST_WORKSPACE_DEPENDENCY_SHARING"
        Files.writeString(config, "$key=SHARED\n")
        val shared =
            (ManagedWorkspaceQuery.open(
                    fixture.installation,
                    root,
                    emptyMap(),
                    WorkspaceSeedHostProbe { _, _, _ -> error("empty store has no donors") },
                ) as Refinement.Refined)
                .value
                .use { it.home }
        Files.delete(config)
        (ManagedWorkspaceQuery.openExisting(fixture.installation, root, emptyMap()) as Refinement.Refined).value.use {
            assertEquals(shared, it.home)
            assertEquals("SHARED", it.environment[key])
        }
        Files.writeString(config, "$key=PRIVATE\n")
        assertEquals(
            Refinement.Rejected(
                WorkspaceConfigurationConflict.DependencySharing(
                    WorkspaceDependencySharing.SHARED,
                    WorkspaceDependencySharing.PRIVATE,
                    ConfigurationSource.SAVED_INSTALLATION,
                    config.toString(),
                    workspace.toString(),
                )
            ),
            ManagedWorkspaceQuery.openExisting(fixture.installation, root, emptyMap()),
        )
        Files.writeString(config, "$key=SHARED\n")
        (ManagedWorkspaceQuery.openExisting(fixture.installation, root, emptyMap()) as Refinement.Refined).value.use {
            assertEquals(shared, it.home)
        }
    }

    @Test
    fun `saved selection changes reject the projected values before workspace creation`() {
        val fixture = productFixture(temporary)
        val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        Files.writeString(workspace.resolve("settings.gradle.kts"), "")
        val root = (FilesystemCanonicalRootDiscovery.discover(workspace) as CanonicalRootDiscovery.Discovered).root
        val config = Files.createDirectories(fixture.installation.resolve("config")).resolve("environment")
        Files.writeString(config, "KAST_GRADLE_IMPORT_VARIABLES=SELECTED_TOKEN\n")
        val values = mapOf("SELECTED_TOKEN" to "value", "STALE_TOKEN" to "old")
        val delegated =
            (GradleImportEnvironment.admit("SELECTED_TOKEN,STALE_TOKEN", "", values) as Refinement.Refined).value
        val product = (InstalledProductRuntime.admit(fixture.installation) as Refinement.Refined).value
        val store = ContainerStore(fixture.runtime.root.parent.parent, fixture.runtime.manifest.platform)

        assertEquals(
            Refinement.Rejected(
                WorkspaceConfigurationConflict.GradleSelection(
                    listOf("SELECTED_TOKEN", "STALE_TOKEN"),
                    listOf("SELECTED_TOKEN"),
                    ConfigurationSource.SAVED_INSTALLATION,
                )
            ),
            ManagedWorkspaceQuery.open(
                product,
                root,
                WorkspaceQueryEnvironment(emptyMap(), delegated),
                WorkspaceSeedHostProbe { _, _, _ -> error("Rejected settings must not inspect a seed Host") },
            ),
        )
        assertTrue(store.workspace(workspace) is Refinement.Rejected)
    }
}
