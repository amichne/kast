package io.github.amichne.kast.appserver

import io.github.amichne.kast.distribution.contract.InstallationLayout
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationParameter
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The coordinator's workspace settings, with no explicit selector, read the files that [InstallationLayout.configuration]
 * names. Stage 8 moves them from the version root to the management root by changing the layout alone, so a reader
 * that derived the files itself would fail here after that change.
 */
class ConfigurationReachTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `unselected workspace settings observe the layout's installation and workspace files`() {
        val root = temporary.toRealPath()
        val layout = InstallationLayout.of(Files.createDirectories(root.resolve("home/.local/share/kast/installation")))
        val workspace = Files.createDirectory(root.resolve("workspace"))
        val caller = mapOf("INSTALLATION_TOKEN" to "installation-value", "WORKSPACE_TOKEN" to "workspace-value")

        write(layout.configuration.environment, "$NAMES=INSTALLATION_TOKEN\n")
        assertEquals(
            listOf(DaemonDelegatedVariable("INSTALLATION_TOKEN", "installation-value")),
            RequestedRuntimeSettings.fromWorkspace(layout.version.path, workspace, caller).wire.delegated,
        )

        write(layout.configuration.workspaceEnvironment(workspace), "$NAMES=WORKSPACE_TOKEN\n")
        assertEquals(
            listOf(DaemonDelegatedVariable("WORKSPACE_TOKEN", "workspace-value")),
            RequestedRuntimeSettings.fromWorkspace(layout.version.path, workspace, caller).wire.delegated,
        )
    }

    private fun write(file: Path, contents: String) {
        Files.createDirectories(file.parent)
        Files.writeString(file, contents)
    }

    private companion object {
        val NAMES = ConfigurationParameter.GRADLE_IMPORT_VARIABLES.key
    }
}
