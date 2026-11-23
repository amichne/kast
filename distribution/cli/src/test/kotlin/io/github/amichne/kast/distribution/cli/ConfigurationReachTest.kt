package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstallationLayout
import io.github.amichne.kast.distribution.contract.WorkspaceConfigurationConflict
import io.github.amichne.kast.distribution.contract.configuration.BrokerPublicEndpointMode
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationParameter
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationSource
import io.github.amichne.kast.distribution.contract.configuration.WorkspaceDependencySharing
import io.github.amichne.kast.distribution.managed.InstalledWorkspaceConfigurationIngress
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Each `:distribution:cli` entry that selects or reads the installation configuration reaches the file that
 * [InstallationLayout.configuration] names. Stage 8 moves that file from the version root to the management root by
 * changing the layout alone, so an entry that derived the file itself would fail here after that change.
 */
class ConfigurationReachTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `the private lifecycle JVM is pointed at the layout's configuration file`() {
        val layout = layout()

        val environment = installationLifecycleEnvironment(temporary, layout.version.path, emptyMap())

        assertEquals(layout.configuration.environment.toString(), environment.getValue(CONFIGURATION_FILE))
    }

    @Test
    fun `the passive runtime observes the endpoint saved in the layout's configuration file`() {
        val layout = layout()
        assertEquals(Refinement.Refined(BrokerPublicEndpointMode.PRIVATE), runtimeEndpointMode(layout.version.path))

        save(layout, "KAST_APP_SERVER_PUBLIC_ENDPOINT=codex-control\n")

        assertEquals(
            Refinement.Refined(BrokerPublicEndpointMode.CODEX_CONTROL),
            runtimeEndpointMode(layout.version.path),
        )
    }

    @Test
    fun `a saved sharing conflict reads and names the layout's configuration file`() {
        val layout = layout()
        val workspace = Files.createDirectory(temporary.toRealPath().resolve("workspace"))
        save(layout, "${ConfigurationParameter.WORKSPACE_DEPENDENCY_SHARING.key}=SHARED\n")
        val configuration =
            (InstalledWorkspaceConfigurationIngress.resolve(layout.version.path, workspace, emptyMap())
                    as Refinement.Refined)
                .value

        val rejected =
            admitWorkspaceSharing(layout.version.path, privateWorkspace(workspace), configuration, emptyMap())

        assertEquals(
            Refinement.Rejected(
                WorkspaceConfigurationConflict.DependencySharing(
                    WorkspaceDependencySharing.PRIVATE,
                    WorkspaceDependencySharing.SHARED,
                    ConfigurationSource.SAVED_INSTALLATION,
                    layout.configuration.environment.toString(),
                    workspace.toString(),
                )
            ),
            rejected,
        )
    }

    private fun layout(): InstallationLayout =
        InstallationLayout.of(
            Files.createDirectories(temporary.toRealPath().resolve("home/.local/share/kast/installation"))
        )

    private fun save(layout: InstallationLayout, contents: String) {
        Files.createDirectories(layout.configuration.directory)
        Files.writeString(layout.configuration.environment, contents)
    }

    private fun privateWorkspace(workspace: Path): AdmittedWorkspace {
        val id = ContainerId.forWorkspace(workspace)
        val name = (ContainerName.parse("workspace") as Refinement.Refined).value
        val directory = temporary.resolve("store/containers/${id.text}")
        return AdmittedWorkspace(
            id,
            name,
            workspace,
            directory,
            directory.resolve("gradle"),
            WorkspaceDependencySharing.PRIVATE,
        )
    }

    private companion object {
        val CONFIGURATION_FILE = ConfigurationParameter.CONFIGURATION_FILE.key
    }
}
