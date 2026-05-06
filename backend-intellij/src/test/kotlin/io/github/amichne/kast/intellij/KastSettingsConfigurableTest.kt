@file:Suppress("INVISIBLE_REFERENCE")

package io.github.amichne.kast.intellij

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
            serverMaxResults = 42
            backendsStandaloneRuntimeLibsDir = "/tmp/runtime-libs"
            telemetryEnabled = true
        }

        val toml = state.toWorkspaceToml()

        assertTrue(toml.contains("[server]"))
        assertTrue(toml.contains("maxResults = 42"))
        assertTrue(toml.contains("[backends.standalone]"))
        assertTrue(toml.contains("runtimeLibsDir = \"/tmp/runtime-libs\""))
        assertTrue(toml.contains("[telemetry]"))
        assertTrue(toml.contains("enabled = true"))
        assertFalse(toml.contains("requestTimeoutMillis"))
    }

    @Test
    fun `state builds override groups from nullable fields`() {
        val state = KastSettingsState().apply {
            serverMaxResults = 42
            indexingRemoteEnabled = true
            telemetryOutputFile = "/tmp/spans.jsonl"
        }
        val override = state.toOverride()

        assertEquals(42, override.server?.maxResults?.value)
        assertEquals(true, override.indexing?.remote?.enabled?.value)
        assertEquals("/tmp/spans.jsonl", override.telemetry?.outputFile?.value?.orNull)
        assertEquals(null, override.cache)
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
