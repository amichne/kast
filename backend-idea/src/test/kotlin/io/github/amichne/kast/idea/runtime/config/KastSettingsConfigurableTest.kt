package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.KastConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class KastSettingsConfigurableTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `default resolved settings produce empty workspace toml`() {
        val state = KastSettingsState().apply {
            loadFromConfig(KastConfig.defaults())
        }

        assertEquals("", state.toWorkspaceToml())
    }

    @Test
    fun `changed settings produce minimal workspace toml`() {
        val state = KastSettingsState().apply {
            loadFromConfig(KastConfig.defaults())
            runtimeDefaultBackend = "idea"
            backendsIdeaEnabled = false
            projectOpenProfileAutoInit = false
            projectOpenAutoExcludeGit = false
            projectOpenGradleLoadEnabled = false
        }

        val toml = state.toWorkspaceToml()

        assertTrue(toml.contains("[runtime]"))
        assertTrue(toml.contains("defaultBackend = \"idea\""))
        assertTrue(toml.contains("[projectOpen]"))
        assertTrue(toml.contains("profileAutoInit = false"))
        assertTrue(toml.contains("autoExcludeGit = false"))
        assertTrue(toml.contains("gradleLoadEnabled = false"))
        assertFalse(toml.contains("[cli]"))
        assertTrue(toml.contains("[backends.idea]"))
        assertTrue(toml.contains("enabled = false"))
        assertFalse(toml.contains("maxResults"))
        assertFalse(toml.contains("runtimeLibsDir"))
        assertFalse(toml.contains("[telemetry]"))
    }

    @Test
    fun `strict plugin matching setting persists to workspace toml`() {
        val state = KastSettingsState().apply {
            loadFromConfig(KastConfig.defaults())
            runtimeStrictPluginMatching = false
        }

        val toml = state.toWorkspaceToml()

        assertTrue(toml.contains("strictPluginMatching = false"))
    }

    @Test
    fun `public settings merge preserves cli binary path as manually edited toml`() {
        val existingToml = """
            [server]
            maxResults = 1000

            [runtime]
            defaultBackend = "headless"

            [runtime.ideaLaunch]
            enabled = true
            command = "/custom/idea"
            waitTimeoutMillis = 12345

            [projectOpen]
            profileAutoInit = false
            profile = "jetbrains-plugin"
            autoExcludeGit = true
            gradleLoadEnabled = true

            [backends.idea]
            enabled = false

            [cli]
            binaryPath = "/old/kast"
        """.trimIndent() + "\n"
        val state = KastSettingsState().apply {
            loadFromConfig(KastConfig.defaults())
            runtimeDefaultBackend = "idea"
            projectOpenProfileAutoInit = false
            projectOpenAutoExcludeGit = false
            projectOpenGradleLoadEnabled = false
        }

        val toml = mergePublicWorkspaceToml(existingToml, state)

        assertTrue(toml.contains("[server]"))
        assertTrue(toml.contains("maxResults = 1000"))
        assertTrue(toml.contains("[runtime.ideaLaunch]"))
        assertTrue(toml.contains("enabled = true"))
        assertTrue(toml.contains("command = \"/custom/idea\""))
        assertTrue(toml.contains("waitTimeoutMillis = 12345"))
        assertTrue(toml.contains("[runtime]"))
        assertTrue(toml.contains("defaultBackend = \"idea\""))
        assertTrue(toml.contains("[projectOpen]"))
        assertTrue(toml.contains("profileAutoInit = false"))
        assertTrue(toml.contains("autoExcludeGit = false"))
        assertTrue(toml.contains("gradleLoadEnabled = false"))
        assertTrue(toml.contains("[cli]"))
        assertTrue(toml.contains("binaryPath = \"/old/kast\""))
        assertFalse(toml.contains("defaultBackend = \"headless\""))
        assertFalse(toml.contains("profileAutoInit = true"))
        assertFalse(toml.contains("autoExcludeGit = true"))
        assertFalse(toml.contains("gradleLoadEnabled = true"))
        assertFalse(toml.contains("binaryPath = \"/new/kast\""))
    }

    @Test
    fun `state builds override groups from nullable fields`() {
        val state = KastSettingsState().apply {
            runtimeDefaultBackend = "idea"
            backendsIdeaEnabled = false
            projectOpenProfileAutoInit = true
            projectOpenProfile = "jetbrains-plugin"
            projectOpenAutoExcludeGit = false
            projectOpenGradleLoadEnabled = false
        }
        val override = state.toOverride()

        assertEquals("idea", override.runtime?.defaultBackend?.value)
        assertEquals(true, override.projectOpen?.profileAutoInit?.value)
        assertEquals("jetbrains-plugin", override.projectOpen?.profile?.value)
        assertEquals(false, override.projectOpen?.autoExcludeGit?.value)
        assertEquals(false, override.projectOpen?.gradleLoadEnabled?.value)
        assertEquals(false, override.backends?.idea?.enabled?.value)
        assertEquals(null, override.cli)
        assertEquals(null, override.server)
        assertEquals(null, override.indexing)
        assertEquals(null, override.telemetry)
    }

    @Test
    fun `global hook settings preserve unrelated config and omit enabled defaults`() {
        val state = KastSettingsState().apply {
            loadFromConfig(KastConfig.defaults())
            codexHooksEnabled = false
            codexPostToolUseEnabled = false
        }

        val toml = mergeGlobalCodexHooksToml(
            """
                [telemetry]
                enabled = true
            """.trimIndent() + "\n",
            state,
        )

        assertTrue(toml.contains("[telemetry]"))
        assertTrue(toml.contains("[codex.hooks]"))
        assertTrue(toml.contains("enabled = false"))
        assertTrue(toml.contains("postToolUse = false"))
        assertFalse(toml.contains("sessionStart"))
    }

    @Test
    fun `settings text replaces the file atomically without staging residue`() {
        val config = tempDir.resolve("config.toml")
        Files.writeString(config, "old")

        writeTextAtomically(config, "new")

        assertEquals("new", Files.readString(config))
        Files.list(tempDir).use { paths ->
            assertEquals(listOf(config), paths.toList())
        }
    }

    @Test
    fun `settings transaction restores the first file when the second write fails`() {
        val workspaceConfig = tempDir.resolve("workspace.toml")
        val globalConfig = tempDir.resolve("global.toml")
        Files.writeString(workspaceConfig, "workspace-old")
        Files.writeString(globalConfig, "global-old")

        assertThrows(IllegalStateException::class.java) {
            writeConfigFilesTransactionally(
                listOf(
                    workspaceConfig to "workspace-new",
                    globalConfig to "global-new",
                ),
                writer = { path, contents ->
                    if (path == globalConfig && contents == "global-new") {
                        error("global write failed")
                    }
                    writeTextAtomically(path, contents)
                },
            )
        }

        assertEquals("workspace-old", Files.readString(workspaceConfig))
        assertEquals("global-old", Files.readString(globalConfig))
    }
}
