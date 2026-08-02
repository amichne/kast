package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.contract.compatibility.RuntimeImplementationVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ServerInstanceDescriptorSerializationTest {
    private val json = Json

    @Test
    fun `descriptor rejects invalid boundary values at deserialization`() {
        val valid = """
            {
              "workspaceRoot": "/workspace",
              "backendName": "headless",
              "backendVersion": "test",
              "transport": "uds",
              "socketPath": "/workspace/.kast/headless.sock",
              "pid": 42,
              "runtimeInstanceId": "550e8400-e29b-41d4-a716-446655440000",
              "processStartEpochMillis": 1,
              "ownerUid": 501,
              "socketFileIdentity": {"device": 1, "inode": 1},
              "schemaVersion": ${DescriptorSchemaVersion.CURRENT.value}
            }
        """.trimIndent()
        val invalidDescriptors = listOf(
            valid.replace(
                "550e8400-e29b-41d4-a716-446655440000",
                "not-a-runtime-instance-id",
            ),
            valid.replace(
                "550e8400-e29b-41d4-a716-446655440000",
                "1-1-1-1-1",
            ),
            valid.replace("\"workspaceRoot\": \"/workspace\"", "\"workspaceRoot\": \"workspace\""),
            valid.replace("\"workspaceRoot\": \"/workspace\"", "\"workspaceRoot\": \"/workspace/../workspace\""),
            valid.replace("\"headless\"", "\"idea\""),
            valid.replace("\"backendVersion\": \"test\"", "\"backendVersion\": \"\""),
            valid.replace("\"backendVersion\": \"test\"", "\"backendVersion\": \"bad version\""),
            valid.replace("\"uds\"", "\"tcp\""),
            valid.replace(
                "\"socketPath\": \"/workspace/.kast/headless.sock\"",
                "\"socketPath\": \"workspace/.kast/headless.sock\"",
            ),
            valid.replace("\"pid\": 42", "\"pid\": 0"),
            valid.replace("\"processStartEpochMillis\": 1", "\"processStartEpochMillis\": 0"),
            valid.replace("\"ownerUid\": 501", "\"ownerUid\": -1"),
            valid.replace("\"inode\": 1", "\"inode\": 0"),
            valid.replace("\"ownerUid\": 501,", ""),
            valid.replace(
                "\"schemaVersion\": ${DescriptorSchemaVersion.CURRENT.value}",
                "\"schemaVersion\": 0",
            ),
        )

        invalidDescriptors.forEach { encoded ->
            assertThrows(IllegalArgumentException::class.java) {
                json.decodeFromString<ServerInstanceDescriptor>(encoded)
            }
        }
    }

    @Test
    fun `owned descriptor keeps the flat compatibility wire format`() {
        val descriptor = ServerInstanceDescriptor(
            workspaceRoot = RuntimeWorkspaceRoot.parse("/workspace"),
            backendVersion = RuntimeImplementationVersion("test"),
            socketPath = RuntimeSocketPath.parse("/workspace/.kast/headless.sock"),
            ownership = ServerInstanceOwnership.Owned(
                runtimeInstanceId = RuntimeInstanceId.parse("550e8400-e29b-41d4-a716-446655440000"),
                processIdentity = RuntimeProcessIdentity(
                    processId = ProcessId.of(42),
                    processStartEpochMillis = ProcessStartEpochMillis.of(1),
                ),
                ownerUid = SocketOwnerUid.of(501),
                socketFileIdentity = SocketFileIdentity.of(device = 1, inode = 2),
            ),
        )
        val compatibilityJson = Json {
            encodeDefaults = true
            explicitNulls = false
        }

        val encoded = compatibilityJson.encodeToJsonElement(ServerInstanceDescriptor.serializer(), descriptor)
            .jsonObject

        assertEquals(
            setOf(
                "workspaceRoot",
                "backendName",
                "backendVersion",
                "transport",
                "socketPath",
                "pid",
                "runtimeInstanceId",
                "processStartEpochMillis",
                "ownerUid",
                "socketFileIdentity",
                "schemaVersion",
            ),
            encoded.keys,
        )
        assertFalse("ownership" in encoded)
        assertFalse("processIdentity" in encoded)
        assertEquals(JsonPrimitive("/workspace"), encoded["workspaceRoot"])
        assertEquals(JsonPrimitive("headless"), encoded["backendName"])
        assertEquals(JsonPrimitive("test"), encoded["backendVersion"])
        assertEquals(JsonPrimitive("uds"), encoded["transport"])
        assertEquals(JsonPrimitive("/workspace/.kast/headless.sock"), encoded["socketPath"])
        assertEquals(JsonPrimitive(42), encoded["pid"])
        assertEquals(JsonPrimitive(DescriptorSchemaVersion.CURRENT.value), encoded["schemaVersion"])
        assertEquals(
            descriptor,
            compatibilityJson.decodeFromJsonElement(ServerInstanceDescriptor.serializer(), encoded),
        )
    }

    @Test
    fun `legacy wire descriptor decodes to explicit legacy ownership`() {
        val descriptor = json.decodeFromString<ServerInstanceDescriptor>(
            """
                {
                  "workspaceRoot": "/workspace",
                  "backendName": "headless",
                  "backendVersion": "test",
                  "transport": "uds",
                  "socketPath": "/workspace/.kast/headless.sock",
                  "pid": 42
                }
            """.trimIndent(),
        )

        val ownership = assertInstanceOf(ServerInstanceOwnership.Legacy::class.java, descriptor.ownership)
        assertEquals(ProcessId.of(42), ownership.processId)
    }

    @Test
    fun `legacy wire descriptor without pid stays non-authoritative`() {
        val descriptor = json.decodeFromString<ServerInstanceDescriptor>(
            """
                {
                  "workspaceRoot": "/workspace",
                  "backendName": "headless",
                  "backendVersion": "test",
                  "transport": "uds",
                  "socketPath": "/workspace/.kast/headless.sock"
                }
            """.trimIndent(),
        )

        val reencoded = json.encodeToJsonElement(ServerInstanceDescriptor.serializer(), descriptor).jsonObject

        assertInstanceOf(ServerInstanceOwnership.LegacyWithoutProcessId::class.java, descriptor.ownership)
        assertFalse("pid" in reencoded)
    }

    @Test
    fun `descriptor rejects unsupported schema version`() {
        val unsupportedSchemaVersion = DescriptorSchemaVersion.CURRENT.value + 1

        assertThrows(IllegalArgumentException::class.java) {
            json.decodeFromString<ServerInstanceDescriptor>(
                """
                    {
                      "workspaceRoot": "/workspace",
                      "backendName": "headless",
                      "backendVersion": "test",
                      "transport": "uds",
                      "socketPath": "/workspace/.kast/headless.sock",
                      "pid": 42,
                      "schemaVersion": $unsupportedSchemaVersion
                    }
                """.trimIndent(),
            )
        }
    }
}
