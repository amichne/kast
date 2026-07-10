package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.CliConfig
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.ProjectOpenConfig
import io.github.amichne.kast.api.client.fields.CliBinaryPath
import io.github.amichne.kast.api.client.fields.ProjectOpenAutoExcludeGit
import io.github.amichne.kast.api.client.fields.ProjectOpenGradleLoadEnabled
import io.github.amichne.kast.api.client.fields.ProjectOpenProfile
import io.github.amichne.kast.api.client.fields.ProjectOpenProfileAutoInit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class KastProjectOpenProfileAutoInitTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `explicitly disabled project-open setup skips without preparing workspace`() {
        val workspace = gradleWorkspace()
        val requests = mutableListOf<PluginWorkspaceBootstrapRequest>()

        val result = KastProjectOpenProfileAutoInit.executeWithDependencies(
            workspaceRoot = workspace,
            config = autoInitConfig(enabled = false),
            prepareWorkspace = { request ->
                requests.add(request)
                PluginWorkspaceBootstrapResult.Prepared(workspace.resolve("unused"), emptyList())
            },
        )

        assertEquals(ProjectOpenProfileAutoInitResult.Skipped("disabled"), result)
        assertEquals(emptyList<PluginWorkspaceBootstrapRequest>(), requests)
    }

    @Test
    fun `enabled project-open profile skips non-Gradle project`() {
        val workspace = tempDir.resolve("workspace")
        Files.createDirectories(workspace)

        val result = KastProjectOpenProfileAutoInit.executeWithDependencies(
            workspaceRoot = workspace,
            config = autoInitConfig(),
            prepareWorkspace = {
                error("bootstrap should not run for non-Gradle workspace")
            },
        )

        assertEquals(ProjectOpenProfileAutoInitResult.Skipped("not a Gradle project"), result)
    }

    @Test
    fun `enabled project-open profile materializes plugin-owned workspace setup`() {
        val workspace = gradleWorkspace()
        val binary = fakeKastBinary()

        val result = KastProjectOpenProfileAutoInit.executeWithDependencies(
            workspaceRoot = workspace,
            config = autoInitConfig(binaryPath = binary),
            loadHomebrewReceipt = matchingHomebrewReceipt(binary),
        )

        assertTrue(result is ProjectOpenProfileAutoInitResult.Installed)
        val installed = result as ProjectOpenProfileAutoInitResult.Installed
        assertEquals(workspace.resolve(".kast/setup/workspace.json"), installed.metadataPath)
        assertEquals(emptyList<Path>(), installed.backups)
        assertTrue(workspace.resolve(".agents/skills/kast/SKILL.md").isRegularFile())
        assertTrue(workspace.resolve("AGENTS.local.md").isRegularFile())
        val guidance = Files.readString(workspace.resolve("AGENTS.local.md"))
        assertTrue(guidance.contains("the IntelliJ plugin owns workspace bootstrap"), guidance)
        val metadata = Files.readString(installed.metadataPath)
        assertTrue(metadata.contains("\"preparedBy\": \"kast-intellij-plugin\""), metadata)
        assertTrue(metadata.contains("\"cliBinary\": \"${binary.toString().jsonEscaped()}\""), metadata)
    }

    @Test
    fun `macOS project-open setup uses Homebrew receipt binary instead of config binary`() {
        val workspace = gradleWorkspace()
        val legacyBinary = fakeKastBinary()
        val homebrewBinary = fakeKastBinary()
        val requests = mutableListOf<PluginWorkspaceBootstrapRequest>()

        val result = KastProjectOpenProfileAutoInit.executeWithDependencies(
            workspaceRoot = workspace,
            config = autoInitConfig(binaryPath = legacyBinary),
            loadHomebrewReceipt = { pluginVersion ->
                MacosHomebrewReceiptLoadResult.Loaded(
                    MacosHomebrewInstallReceipt(
                        cliBinary = homebrewBinary,
                        formulaPrefix = homebrewBinary.parent,
                        cliVersion = pluginVersion,
                        caskToken = "amichne/kast/kast-plugin",
                        pluginVersion = pluginVersion,
                    ),
                )
            },
            prepareWorkspace = { request ->
                requests.add(request)
                PluginWorkspaceBootstrapResult.Prepared(
                    workspace.resolve(".kast/setup/workspace.json"),
                    emptyList(),
                )
            },
        )

        assertTrue(result is ProjectOpenProfileAutoInitResult.Installed)
        assertEquals(homebrewBinary, requests.single().cliBinary)
    }

    @Test
    fun `non-macOS project-open setup keeps configured binary authority`() {
        val workspace = gradleWorkspace()
        val configuredBinary = fakeKastBinary()
        val requests = mutableListOf<PluginWorkspaceBootstrapRequest>()

        val result = KastProjectOpenProfileAutoInit.executeWithConfiguredBinary(
            workspaceRoot = workspace,
            config = autoInitConfig(binaryPath = configuredBinary),
            prepareWorkspace = { request ->
                requests.add(request)
                PluginWorkspaceBootstrapResult.Prepared(
                    workspace.resolve(".kast/setup/workspace.json"),
                    emptyList(),
                )
            },
        )

        assertTrue(result is ProjectOpenProfileAutoInitResult.Installed)
        assertEquals(configuredBinary, requests.single().cliBinary)
    }

    @Test
    fun `legacy copilot project-open profile remains supported compatibility input`() {
        val workspace = gradleWorkspace()
        val requests = mutableListOf<PluginWorkspaceBootstrapRequest>()

        val result = KastProjectOpenProfileAutoInit.executeWithDependencies(
            workspaceRoot = workspace,
            config = autoInitConfig(profile = ProjectOpenProfile.COPILOT_LSP),
            loadHomebrewReceipt = matchingHomebrewReceipt(fakeKastBinary()),
            prepareWorkspace = { request ->
                requests.add(request)
                PluginWorkspaceBootstrapResult.Prepared(workspace.resolve(".kast/setup/workspace.json"), emptyList())
            },
        )

        assertTrue(result is ProjectOpenProfileAutoInitResult.Installed)
        assertEquals(1, requests.size)
    }

    @Test
    fun `plugin bootstrap backs up and removes unknown prior managed artifacts`() {
        val workspace = gradleWorkspace()
        val binary = fakeKastBinary()
        Files.createDirectories(workspace.resolve(".agents/instructions/kast"))
        Files.writeString(workspace.resolve(".agents/instructions/kast/README.md"), "old")
        Files.createDirectories(workspace.resolve(".github/extensions/kast"))
        Files.writeString(workspace.resolve(".github/extensions/kast/extension.mjs"), "old")
        Files.createDirectories(workspace.resolve(".agents/skills/kast"))
        Files.writeString(workspace.resolve(".agents/skills/kast/old.txt"), "old")

        val result = KastProjectOpenProfileAutoInit.executeWithDependencies(
            workspaceRoot = workspace,
            config = autoInitConfig(binaryPath = binary),
            loadHomebrewReceipt = matchingHomebrewReceipt(binary),
        )

        assertTrue(result is ProjectOpenProfileAutoInitResult.Installed)
        val installed = result as ProjectOpenProfileAutoInitResult.Installed
        assertTrue(installed.backups.isNotEmpty())
        assertFalse(workspace.resolve(".agents/instructions/kast").exists())
        assertFalse(workspace.resolve(".github/extensions/kast").exists())
        assertFalse(workspace.resolve(".agents/skills/kast/old.txt").exists())
        assertTrue(workspace.resolve(".agents/skills/kast/SKILL.md").isRegularFile())
        assertTrue(installed.backups.all { backup -> backup.startsWith(workspace.resolve(".kast/backups")) })
    }

    @Test
    fun `missing cli binary fails closed`() {
        val workspace = gradleWorkspace()

        val result = KastProjectOpenProfileAutoInit.executeWithDependencies(
            workspaceRoot = workspace,
            config = autoInitConfig(binaryPath = workspace.resolve("missing-kast")),
            loadHomebrewReceipt = matchingHomebrewReceipt(workspace.resolve("missing-kast")),
        )

        assertTrue(result is ProjectOpenProfileAutoInitResult.Failed)
        assertTrue((result as ProjectOpenProfileAutoInitResult.Failed).message.contains("Kast CLI binary is missing"))
        assertFalse(workspace.resolve(".agents/skills/kast/SKILL.md").exists())
    }

    private fun gradleWorkspace(): Path {
        val workspace = tempDir.resolve("workspace-${System.nanoTime()}")
        Files.createDirectories(workspace)
        Files.writeString(workspace.resolve("settings.gradle.kts"), "")
        return workspace
    }

    private fun fakeKastBinary(): Path {
        val binary = tempDir.resolve("bin/kast-${System.nanoTime()}")
        Files.createDirectories(binary.parent)
        Files.writeString(binary, "#!/usr/bin/env sh\n")
        return binary
    }

    private fun autoInitConfig(
        enabled: Boolean = true,
        profile: String = ProjectOpenProfile.JETBRAINS_PLUGIN,
        binaryPath: Path = fakeKastBinary(),
    ): KastConfig =
        KastConfig.defaults().copy(
            projectOpen = ProjectOpenConfig(
                profileAutoInit = ProjectOpenProfileAutoInit(enabled),
                profile = ProjectOpenProfile(profile),
                autoExcludeGit = ProjectOpenAutoExcludeGit(true),
                gradleLoadEnabled = ProjectOpenGradleLoadEnabled(true),
            ),
            cli = CliConfig(CliBinaryPath(binaryPath.toString())),
        )

    private fun matchingHomebrewReceipt(
        binary: Path,
    ): (PluginVersion) -> MacosHomebrewReceiptLoadResult = { pluginVersion ->
        MacosHomebrewReceiptLoadResult.Loaded(
            MacosHomebrewInstallReceipt(
                cliBinary = binary,
                formulaPrefix = binary.parent,
                cliVersion = pluginVersion,
                caskToken = "amichne/kast/kast-plugin",
                pluginVersion = pluginVersion,
            ),
        )
    }
}

private fun Path.exists(): Boolean = Files.exists(this)

private fun Path.isRegularFile(): Boolean = Files.isRegularFile(this)

private fun String.jsonEscaped(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")
