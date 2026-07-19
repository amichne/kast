package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.CliConfig
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.ProjectOpenConfig
import io.github.amichne.kast.api.client.fields.CliBinaryPath
import io.github.amichne.kast.api.client.fields.ProjectOpenAutoExcludeGit
import io.github.amichne.kast.api.client.fields.ProjectOpenGradleLoadEnabled
import io.github.amichne.kast.api.client.fields.ProjectOpenProfile
import io.github.amichne.kast.api.client.fields.ProjectOpenProfileAutoInit
import io.github.amichne.kast.api.contract.compatibility.CliImplementationVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
            loadMachineManifest = matchingMachineManifest(binary),
        )

        assertTrue(result is ProjectOpenProfileAutoInitResult.Installed)
        val installed = result as ProjectOpenProfileAutoInitResult.Installed
        assertEquals(workspace.resolve(".kast/setup/workspace.json"), installed.metadataPath)
        assertEquals(emptyList<Path>(), installed.backups)
        assertTrue(workspace.resolve(".agents/skills/kast/SKILL.md").isRegularFile())
        assertTrue(workspace.resolve("AGENTS.local.md").isRegularFile())
        val skill = Files.readString(workspace.resolve(".agents/skills/kast/SKILL.md"))
        assertTrue(skill.contains("kast-cli-dialect-revision: \"2\""), skill)
        assertTrue(skill.contains("For every delegated worker using a linked Git worktree"), skill)
        assertTrue(skill.contains("Before the worker starts, open the exact worktree root"), skill)
        assertTrue(skill.contains("Never reuse another worktree's Kast runtime, metadata, or semantic evidence"), skill)
        assertTrue(skill.contains("Keep that IDE project open while the worker and worktree are active"), skill)
        assertTrue(skill.contains("close that exact IDE project or window before removing the worktree"), skill)
        val guidance = Files.readString(workspace.resolve("AGENTS.local.md"))
        assertTrue(guidance.contains("the IntelliJ plugin owns workspace bootstrap"), guidance)
        assertTrue(guidance.contains("Before each linked worker starts"), guidance)
        assertTrue(guidance.contains("close its exact IDE project or window before removing the worktree"), guidance)
        val metadata = Files.readString(installed.metadataPath)
        assertTrue(metadata.contains("\"preparedBy\": \"kast-intellij-plugin\""), metadata)
        assertTrue(metadata.contains("\"cliBinary\": \"${binary.toString().jsonEscaped()}\""), metadata)
        val metadataObject = Json.parseToJsonElement(metadata).jsonObject
        assertEquals(3, metadataObject.getValue("schemaVersion").jsonPrimitive.int)
        assertFalse(metadataObject.containsKey("pluginVersion"))
        assertFalse(metadataObject.containsKey("cliVersion"))
        val compatibility = metadataObject.getValue("compatibility").jsonObject
        assertEquals(1, compatibility.getValue("protocolRevision").jsonPrimitive.int)
        assertEquals(3, compatibility.getValue("workspaceMetadataRevision").jsonPrimitive.int)
        assertEquals("IDEA", compatibility.getValue("runtimeIdentity").jsonObject.getValue("backendKind").jsonPrimitive.content)
        assertTrue(
            compatibility.getValue("readCapabilities").jsonArray
                .any { capability -> capability.jsonPrimitive.content == "DIAGNOSTICS" },
        )
        assertTrue(
            compatibility.getValue("mutationCapabilities").jsonArray
                .any { capability -> capability.jsonPrimitive.content == "RENAME" },
        )
    }

    @Test
    fun `macOS project-open setup uses machine manifest binary instead of config binary`() {
        val workspace = gradleWorkspace()
        val legacyBinary = fakeKastBinary()
        val machineBinary = fakeKastBinary()
        val requests = mutableListOf<PluginWorkspaceBootstrapRequest>()

        val result = KastProjectOpenProfileAutoInit.executeWithDependencies(
            workspaceRoot = workspace,
            config = autoInitConfig(binaryPath = legacyBinary),
            loadMachineManifest = {
                MacosMachineManifestLoadResult.Loaded(
                    binary = machineBinary,
                    version = CliImplementationVersion("machine-cli-version"),
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
        assertEquals(machineBinary, requests.single().cliBinary)
        assertEquals("machine-cli-version", requests.single().cliVersion.value)
    }

    @Test
    fun `non-macOS project-open setup keeps configured binary authority`() {
        val workspace = gradleWorkspace()
        val configuredBinary = fakeKastBinary()
        val requests = mutableListOf<PluginWorkspaceBootstrapRequest>()

        val result = KastProjectOpenProfileAutoInit.executeWithConfiguredBinary(
            workspaceRoot = workspace,
            config = autoInitConfig(binaryPath = configuredBinary),
            loadCliVersion = { CliImplementationVersion("configured-cli-version") },
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
        assertEquals("configured-cli-version", requests.single().cliVersion.value)
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
            loadMachineManifest = matchingMachineManifest(binary),
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
            loadMachineManifest = matchingMachineManifest(workspace.resolve("missing-kast")),
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

    private fun matchingMachineManifest(
        binary: Path,
    ): () -> MacosMachineManifestLoadResult = {
        MacosMachineManifestLoadResult.Loaded(
            binary = binary,
            version = CliImplementationVersion("0.13.0"),
        )
    }
}

private fun Path.exists(): Boolean = Files.exists(this)

private fun Path.isRegularFile(): Boolean = Files.isRegularFile(this)

private fun String.jsonEscaped(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")
