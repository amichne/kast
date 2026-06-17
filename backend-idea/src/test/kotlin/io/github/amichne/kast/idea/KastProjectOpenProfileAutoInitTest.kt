package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.CliConfig
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.ProjectOpenConfig
import io.github.amichne.kast.api.client.fields.CliBinaryPath
import io.github.amichne.kast.api.client.fields.ProjectOpenAutoExcludeGit
import io.github.amichne.kast.api.client.fields.ProjectOpenProfile
import io.github.amichne.kast.api.client.fields.ProjectOpenProfileAutoInit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class KastProjectOpenProfileAutoInitTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `disabled project-open profile skips without running command`() {
        val workspace = tempDir.resolve("workspace")
        Files.createDirectories(workspace)
        Files.writeString(workspace.resolve("settings.gradle.kts"), "")
        val commands = mutableListOf<List<String>>()

        val result = KastProjectOpenProfileAutoInit.execute(
            workspaceRoot = workspace,
            config = KastConfig.defaults(),
            runCommand = { command ->
                commands.add(command)
                CommandRunResult(success = true, message = "")
            },
        )

        assertEquals(ProjectOpenProfileAutoInitResult.Skipped("disabled"), result)
        assertEquals(emptyList<List<String>>(), commands)
    }

    @Test
    fun `enabled project-open profile skips non-Gradle project`() {
        val workspace = tempDir.resolve("workspace")
        Files.createDirectories(workspace)

        val result = KastProjectOpenProfileAutoInit.execute(
            workspaceRoot = workspace,
            config = autoInitConfig(),
            runCommand = { CommandRunResult(success = true, message = "") },
        )

        assertEquals(ProjectOpenProfileAutoInitResult.Skipped("not a Gradle project"), result)
    }

    @Test
    fun `enabled project-open profile installs copilot package for Gradle project`() {
        val workspace = tempDir.resolve("workspace")
        Files.createDirectories(workspace)
        Files.writeString(workspace.resolve("settings.gradle.kts"), "")
        val commands = mutableListOf<List<String>>()

        val result = KastProjectOpenProfileAutoInit.execute(
            workspaceRoot = workspace,
            config = autoInitConfig(autoExcludeGit = true),
            runCommand = { command ->
                commands.add(command)
                CommandRunResult(success = true, message = "ok")
            },
        )

        assertTrue(result is ProjectOpenProfileAutoInitResult.Installed)
        assertEquals(
            listOf(
                "/opt/kast/bin/kast",
                "install",
                "copilot",
                "--target-dir",
                workspace.resolve(".github").toAbsolutePath().normalize().toString(),
            ),
            commands.single(),
        )
    }

    @Test
    fun `auto exclude opt-out is passed to copilot install command`() {
        val workspace = tempDir.resolve("workspace")
        Files.createDirectories(workspace)
        Files.writeString(workspace.resolve("build.gradle"), "")

        val command = KastProjectOpenProfileAutoInit.buildInstallCommand(
            workspaceRoot = workspace,
            config = autoInitConfig(autoExcludeGit = false),
        )

        assertEquals("--no-auto-exclude-git", command.last())
    }

    @Test
    fun `failed project-open install returns non-throwing failure result`() {
        val workspace = tempDir.resolve("workspace")
        Files.createDirectories(workspace)
        Files.writeString(workspace.resolve("settings.gradle"), "")

        val result = KastProjectOpenProfileAutoInit.execute(
            workspaceRoot = workspace,
            config = autoInitConfig(),
            runCommand = { CommandRunResult(success = false, message = "nope") },
        )

        assertTrue(result is ProjectOpenProfileAutoInitResult.Failed)
        assertEquals("nope", (result as ProjectOpenProfileAutoInitResult.Failed).message)
    }

    private fun autoInitConfig(autoExcludeGit: Boolean = true): KastConfig =
        KastConfig.defaults().copy(
            projectOpen = ProjectOpenConfig(
                profileAutoInit = ProjectOpenProfileAutoInit(true),
                profile = ProjectOpenProfile(ProjectOpenProfile.COPILOT_LSP),
                autoExcludeGit = ProjectOpenAutoExcludeGit(autoExcludeGit),
            ),
            cli = CliConfig(CliBinaryPath("/opt/kast/bin/kast")),
        )
}
