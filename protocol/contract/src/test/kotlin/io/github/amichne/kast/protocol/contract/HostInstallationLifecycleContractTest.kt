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
import org.junit.jupiter.api.assertThrows

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
    fun `removed reload capabilities and commands reject at decoding`() {
        for (capability in listOf("HOST_RELOAD_REFRESH", "HOST_PREPARED_RELOAD_REFRESH")) {
            assertThrows<SerializationException> {
                json.decodeFromString<IdeLifecycleCapabilityName>(json.encodeToString(capability))
            }
        }
        for (type in listOf("settle_host_for_reload", "refresh_host_bundle")) {
            val retired = json.encodeToString(RetiredHostCommand(type, "host", "update"))
            assertThrows<SerializationException> { json.decodeFromString<IdeLifecycleCommand>(retired) }
        }
        val retired = json.encodeToString(RetiredHostCommand("host_refresh_requested", "host", "update"))
        assertThrows<SerializationException> { json.decodeFromString<IdeLifecycleResult>(retired) }
    }

    @Test
    fun `installer commands preserve exact Host and transaction selectors`() {
        val commands =
            listOf(
                "settle_host" to IdeLifecycleCommand.SettleHost("host", "update"),
                "resume_host" to IdeLifecycleCommand.ResumeHost("host", "update"),
                "stop_host" to IdeLifecycleCommand.StopHost("host", "update"),
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
            )) {
            val encoded = json.parseToJsonElement(json.encodeToString<IdeLifecycleResult>(result)).jsonObject
            assertEquals(type, encoded.getValue("type").jsonPrimitive.content)
            val runtime = encoded.getValue("runtimeIdentity").jsonObject
            assertEquals(
                setOf("ideaHome", "profile", "pid", "processStartEpochMillis", "hostLifetime", "buildIdentity"),
                runtime.keys,
            )
            assertEquals("42", runtime.getValue("pid").jsonPrimitive.content)
            assertEquals("100", runtime.getValue("processStartEpochMillis").jsonPrimitive.content)
            assertEquals(
                "a".repeat(40),
                runtime.getValue("buildIdentity").jsonObject.getValue("sourceRevision").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `unknown installer operations reject at decoding`() {
        val unsupported =
            json
                .encodeToString<IdeLifecycleCommand>(IdeLifecycleCommand.StopHost("host", "update"))
                .replace("stop_host", "force_stop_host")
        assertThrows(SerializationException::class.java) { json.decodeFromString<IdeLifecycleCommand>(unsupported) }
    }

    @Test
    fun `removed automatic plugin reload member rejects at decoding`() {
        val encoded = json.encodeToString(identity)
        assertEquals(identity, json.decodeFromString<IdeHostRuntimeIdentity>(encoded))
        // The desktop reload switch is gone; neither its retired nor its former default value decodes.
        for (value in listOf("ENABLED", "DISABLED")) {
            val retired = encoded.replaceFirst("{", "{\"autoReload\":\"$value\",")
            assertThrows(SerializationException::class.java) { json.decodeFromString<IdeHostRuntimeIdentity>(retired) }
        }
    }
}

/** The wire shape the removed reload commands and refresh acknowledgment used. */
@kotlinx.serialization.Serializable
private data class RetiredHostCommand(val type: String, val host: String, val transaction: String)
