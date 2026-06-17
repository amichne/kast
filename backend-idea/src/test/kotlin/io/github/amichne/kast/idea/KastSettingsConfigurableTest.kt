@file:Suppress("INVISIBLE_REFERENCE")

package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.KastConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KastSettingsConfigurableTest {
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
            cliBinaryPath = "/tmp/kast"
            backendsIdeaEnabled = false
            projectOpenProfileAutoInit = true
            projectOpenAutoExcludeGit = false
        }

        val toml = state.toWorkspaceToml()

        assertTrue(toml.contains("[runtime]"))
        assertTrue(toml.contains("defaultBackend = \"idea\""))
        assertTrue(toml.contains("[projectOpen]"))
        assertTrue(toml.contains("profileAutoInit = true"))
        assertTrue(toml.contains("autoExcludeGit = false"))
        assertTrue(toml.contains("[cli]"))
        assertTrue(toml.contains("binaryPath = \"/tmp/kast\""))
        assertTrue(toml.contains("[backends.idea]"))
        assertTrue(toml.contains("enabled = false"))
        assertFalse(toml.contains("maxResults"))
        assertFalse(toml.contains("runtimeLibsDir"))
        assertFalse(toml.contains("[telemetry]"))
    }

    @Test
    fun `legacy tuning fields are not emitted from plugin settings`() {
        val state = KastSettingsState().apply {
            loadFromConfig(KastConfig.defaults())
            serverMaxResults = 42
            backendsHeadlessRuntimeLibsDir = "/tmp/runtime-libs"
            telemetryEnabled = true
        }

        val toml = state.toWorkspaceToml()

        assertEquals("", toml)
    }

    @Test
    fun `public settings merge preserves unrelated advanced toml`() {
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
            profile = "copilot-lsp"
            autoExcludeGit = true

            [backends.idea]
            enabled = false

            [cli]
            binaryPath = "/old/kast"
        """.trimIndent() + "\n"
        val state = KastSettingsState().apply {
            loadFromConfig(KastConfig.defaults())
            runtimeDefaultBackend = "idea"
            projectOpenProfileAutoInit = true
            projectOpenAutoExcludeGit = false
            cliBinaryPath = "/new/kast"
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
        assertTrue(toml.contains("profileAutoInit = true"))
        assertTrue(toml.contains("autoExcludeGit = false"))
        assertTrue(toml.contains("[cli]"))
        assertTrue(toml.contains("binaryPath = \"/new/kast\""))
        assertFalse(toml.contains("defaultBackend = \"headless\""))
        assertFalse(toml.contains("profileAutoInit = false"))
        assertFalse(toml.contains("autoExcludeGit = true"))
        assertFalse(toml.contains("binaryPath = \"/old/kast\""))
    }

    @Test
    fun `state builds override groups from nullable fields`() {
        val state = KastSettingsState().apply {
            runtimeDefaultBackend = "idea"
            backendsIdeaEnabled = false
            projectOpenProfileAutoInit = true
            projectOpenProfile = "copilot-lsp"
            projectOpenAutoExcludeGit = false
            cliBinaryPath = "/tmp/kast"
        }
        val override = state.toOverride()

        assertEquals("idea", override.runtime?.defaultBackend?.value)
        assertEquals(true, override.projectOpen?.profileAutoInit?.value)
        assertEquals("copilot-lsp", override.projectOpen?.profile?.value)
        assertEquals(false, override.projectOpen?.autoExcludeGit?.value)
        assertEquals(false, override.backends?.idea?.enabled?.value)
        assertEquals("/tmp/kast", override.cli?.binaryPath?.value)
        assertEquals(null, override.server)
        assertEquals(null, override.indexing)
        assertEquals(null, override.telemetry)
    }

    @Test
    fun `telemetry detail level maps config values for settings UI`() {
        assertEquals(KastTelemetryDetailLevel.BASIC, KastTelemetryDetailLevel.fromConfigValue(null))
        assertEquals(KastTelemetryDetailLevel.BASIC, KastTelemetryDetailLevel.fromConfigValue(" "))
        assertEquals(KastTelemetryDetailLevel.BASIC, KastTelemetryDetailLevel.fromConfigValue("unexpected"))
        assertEquals(KastTelemetryDetailLevel.BASIC, KastTelemetryDetailLevel.fromConfigValue("BaSiC"))
        assertEquals(KastTelemetryDetailLevel.VERBOSE, KastTelemetryDetailLevel.fromConfigValue(" verbose "))
    }
}
