package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HostInstallationLifecycleContractTest {
    private val json = Json { encodeDefaults = true }
    private val identity =
        IdeHostRuntimeIdentity(
            "/idea",
            "/profile",
            42,
            100,
            "host",
            IdeHostBuildIdentity("1.2.3", "a".repeat(40), "b".repeat(40)),
        )

    @Test
    fun `old refresh capability decodes without granting prepared refresh`() {
        val encoded = json.encodeToString(IdeLifecycleCapabilityName.HOST_RELOAD_REFRESH)
        assertEquals("HOST_RELOAD_REFRESH", json.parseToJsonElement(encoded).jsonPrimitive.content)
        assertEquals(
            IdeLifecycleCapabilityName.HOST_RELOAD_REFRESH,
            json.decodeFromString<IdeLifecycleCapabilityName>(encoded),
        )
        assertEquals(
            "HOST_PREPARED_RELOAD_REFRESH",
            json
                .parseToJsonElement(json.encodeToString(IdeLifecycleCapabilityName.HOST_PREPARED_RELOAD_REFRESH))
                .jsonPrimitive
                .content,
        )
        assertEquals(
            "HOST_REFRESH_MARKER_UNSIGNALED",
            json
                .parseToJsonElement(json.encodeToString(IdeLifecycleFailure.HOST_REFRESH_MARKER_UNSIGNALED))
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun `installer commands preserve exact Host and transaction selectors`() {
        val commands =
            listOf(
                "settle_host" to IdeLifecycleCommand.SettleHost("host", "update"),
                "settle_host_for_reload" to IdeLifecycleCommand.SettleHostForReload("host", "update"),
                "resume_host" to IdeLifecycleCommand.ResumeHost("host", "update"),
                "stop_host" to IdeLifecycleCommand.StopHost("host", "update"),
                "refresh_host_bundle" to IdeLifecycleCommand.RefreshHostBundle("host", "update"),
            )
        for ((name, command) in commands) {
            val encoded = json.parseToJsonElement(json.encodeToString<IdeLifecycleCommand>(command)).jsonObject
            assertEquals(setOf("type", "host", "transaction"), encoded.keys)
            assertEquals(name, encoded.getValue("type").jsonPrimitive.content)
            assertEquals("host", encoded.getValue("host").jsonPrimitive.content)
            assertEquals("update", encoded.getValue("transaction").jsonPrimitive.content)
        }
    }

    @Test
    fun `settlement and stop acknowledgment carry distinct finite evidence`() {
        val settling =
            json
                .parseToJsonElement(
                    json.encodeToString<IdeLifecycleResult>(IdeLifecycleResult.HostSettling("host", "update"))
                )
                .jsonObject
        assertEquals("host_settling", settling.getValue("type").jsonPrimitive.content)
        assertFalse("runtimeIdentity" in settling)
        for ((type, result) in
            listOf(
                "host_settled" to IdeLifecycleResult.HostSettled("host", "update", identity),
                "host_stop_requested" to IdeLifecycleResult.HostStopRequested("host", "update", identity),
                "host_refresh_requested" to IdeLifecycleResult.HostRefreshRequested("host", "update", identity),
            )) {
            val encoded = json.parseToJsonElement(json.encodeToString<IdeLifecycleResult>(result)).jsonObject
            assertEquals(type, encoded.getValue("type").jsonPrimitive.content)
            val runtime = encoded.getValue("runtimeIdentity").jsonObject
            assertEquals("42", runtime.getValue("pid").jsonPrimitive.content)
            assertEquals("100", runtime.getValue("processStartEpochMillis").jsonPrimitive.content)
            assertEquals("DISABLED", runtime.getValue("autoReload").jsonPrimitive.content)
            assertEquals(
                "a".repeat(40),
                runtime.getValue("buildIdentity").jsonObject.getValue("sourceRevision").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `unknown installer operations and auto-reload values reject at decoding`() {
        val unsupported =
            json
                .encodeToString<IdeLifecycleCommand>(IdeLifecycleCommand.StopHost("host", "update"))
                .replace("stop_host", "force_stop_host")
        assertThrows(SerializationException::class.java) { json.decodeFromString<IdeLifecycleCommand>(unsupported) }
        val invalid = json.encodeToString(identity).replace("DISABLED", "UNPROVEN")
        assertThrows(SerializationException::class.java) { json.decodeFromString<IdeHostRuntimeIdentity>(invalid) }
    }
}
