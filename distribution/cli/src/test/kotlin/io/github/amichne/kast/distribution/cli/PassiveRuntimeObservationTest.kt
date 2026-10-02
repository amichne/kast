package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.HostedServiceStatus
import io.github.amichne.kast.distribution.contract.HostedServiceUnavailableFailure
import java.io.DataInputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Serializable private data class TestRuntimeReply(val type: String, val value: String)

class PassiveRuntimeObservationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `status admits the producer readiness receipt and sends required request fields`() {
        val installation = temporary.toRealPath()
        val profile =
            MessageDigest.getInstance("SHA-256")
                .digest("/fixture/codex".toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(16)
        val readiness = installation.resolve("state/broker/$profile/service-readiness.json")
        Files.createDirectories(readiness.parent)
        fun write(relative: String, value: String) = Files.writeString(installation.resolve(relative), value)
        write(
            "installation.json",
            Json.encodeToString(RuntimeFixtureManifest(3, installation.toString(), "/fixture/codex")),
        )
        write("state/epoch.json", Json.encodeToString(RuntimeFixtureEpoch(1, "sha256:" + "a".repeat(64), "epoch")))
        Files.writeString(
            readiness,
            Json.encodeToString(RuntimeFixtureReadiness("ready", 3, "12345678-1234-1234-1234-123456789abc")),
        )
        var requests = 0
        val observed =
            observeRuntime(installation) { _, request ->
                requests++
                val fields = Json.parseToJsonElement(request).jsonObject
                assertEquals(setOf("type", "version"), fields.keys)
                assertEquals("status", fields.getValue("type").jsonPrimitive.content)
                assertEquals(1, fields.getValue("version").jsonPrimitive.int)
                Json.encodeToString(
                    RuntimeFixtureStatus(
                        "status",
                        RuntimeFixtureCoordinator(
                            "sha256:" + "a".repeat(64),
                            "epoch",
                            "12345678-1234-1234-1234-123456789abc",
                            "configuration",
                        ),
                        RuntimeFixtureProjection("0.50.0", emptyList(), 0),
                    )
                )
            }
        assertEquals(1, requests)
        assertEquals(Observation.verified("0.50.0"), observed.loadedVersion)
        assertEquals(Observation.verified(emptyList<String>()), observed.activeWorkspaces)
        val admitted = assertInstanceOf(PassiveRuntimeObservation.Observed::class.java, observed)
        assertEquals("12345678-1234-1234-1234-123456789abc", admitted.generation.value)
        assertEquals(ObservationState.UNAVAILABLE, admitted.hostedServices.state)
    }

    @Test
    fun `status preserves a finite receipt failure without contacting the socket`() {
        val result = observeRuntime(temporary) { _, _ -> error("unexpected socket effect") }
        assertEquals(PassiveRuntimeObservation.Rejected(RuntimeObservationFailure.EPOCH_UNAVAILABLE), result)
        assertEquals("EPOCH_UNAVAILABLE", result.loadedVersion.reason)
    }

    @Test
    fun `passive management request includes required type and protocol version`() {
        val request = Json.parseToJsonElement(runtimeStatusRequestDocument()).jsonObject
        assertEquals(setOf("type", "version"), request.keys)
        assertEquals("status", request.getValue("type").jsonPrimitive.content)
        assertEquals("1", request.getValue("version").jsonPrimitive.content)
    }

    @Test
    fun `mixed versions retain each live host identity and proven compatibility`() {
        val first =
            HostedServiceStatus.Compatible("/workspace/one", "00000000-0000-0000-0000-000000000001", 123, "0.49.0")
        val second =
            HostedServiceStatus.Compatible("/workspace/two", "00000000-0000-0000-0000-000000000002", 456, "0.48.9")
        val observation = projectHostedServices(listOf(first, second))
        assertEquals(ObservationState.VERIFIED, observation.state)
        assertEquals(listOf(first, second), observation.value)
    }

    @Test
    fun `missing live host evidence remains unavailable`() {
        assertEquals(ObservationState.UNAVAILABLE, projectHostedServices(null).state)
        assertEquals(ObservationState.UNAVAILABLE, projectHostedServices(emptyList()).state)
        val unavailable =
            HostedServiceStatus.Unavailable("/workspace", HostedServiceUnavailableFailure.HOST_UNAVAILABLE)
        assertEquals(listOf(unavailable), projectHostedServices(listOf(unavailable)).value)
    }

    @Test
    fun `registered root failure retains registry evidence without a fabricated host identity`() {
        val rejected =
            HostedServiceStatus.RegistryUnavailable(
                "/installation/config/workspaces.json",
                io.github.amichne.kast.distribution.contract.HostedRegistryFailure.PATH_REJECTED,
            )
        assertEquals(listOf(rejected), projectHostedServices(listOf(rejected)).value)
        assertEquals(
            ObservationState.UNAVAILABLE,
            projectHostedServices(listOf(rejected.copy(registryPath = "relative"))).state,
        )
    }

    @Test
    fun `unproven host identity or provenance rejects the observation`() {
        val valid = HostedServiceStatus.Compatible("/workspace", "00000000-0000-0000-0000-000000000001", 123, "0.49.0")
        listOf(
                valid.copy(host = "not-a-host"),
                valid.copy(hostPid = 0),
                valid.copy(hostedPluginVersion = "unknown"),
                valid.copy(root = "relative"),
            )
            .forEach { assertEquals(ObservationState.UNAVAILABLE, projectHostedServices(listOf(it)).state) }
    }

    @Test
    fun `passive Unix websocket exchange reads one bounded status response`() {
        val socket = temporary.resolve("management.sock")
        val reply = Json.encodeToString(TestRuntimeReply("status", "ready".repeat(32)))
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(UnixDomainSocketAddress.of(socket))
            val worker = Executors.newSingleThreadExecutor()
            try {
                val served = worker.submit {
                    server.accept().use { client ->
                        val input = DataInputStream(Channels.newInputStream(client))
                        val output = Channels.newOutputStream(client)
                        val headers = StringBuilder()
                        while (!headers.endsWith("\r\n\r\n")) headers.append(input.readUnsignedByte().toChar())
                        val key =
                            headers.lines().single { it.startsWith("Sec-WebSocket-Key:") }.substringAfter(':').trim()
                        val accept =
                            Base64.getEncoder()
                                .encodeToString(
                                    MessageDigest.getInstance("SHA-1")
                                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                                )
                        output.write(
                            ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n" +
                                    "Connection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n")
                                .toByteArray()
                        )
                        output.flush()
                        assertEquals(0x81, input.readUnsignedByte())
                        val length = input.readUnsignedByte() and 0x7f
                        val mask = ByteArray(4).also(input::readFully)
                        val request = ByteArray(length).also(input::readFully)
                        request.indices.forEach {
                            request[it] = (request[it].toInt() xor mask[it % 4].toInt()).toByte()
                        }
                        assertEquals("status", request.decodeToString())
                        val bytes = reply.toByteArray()
                        output.write(
                            byteArrayOf(0x81.toByte(), 126, (bytes.size shr 8).toByte(), bytes.size.toByte()) + bytes
                        )
                        output.flush()
                    }
                }
                assertEquals(reply, boundedExchange(socket, "status"))
                served.get(2, TimeUnit.SECONDS)
            } finally {
                worker.shutdownNow()
            }
        }
    }
}

@Serializable
private data class RuntimeFixtureManifest(val schemaVersion: Int, val installationRoot: String, val codexHome: String)

@Serializable
private data class RuntimeFixtureEpoch(val schemaVersion: Int, val installation: String, val epoch: String)

@Serializable
private data class RuntimeFixtureReadiness(val state: String, val schemaVersion: Int, val serviceInstanceId: String)

@Serializable
private data class RuntimeFixtureCoordinator(
    val installationId: String,
    val stateEpoch: String,
    val serviceGeneration: String,
    val configurationIdentity: String,
)

@Serializable
private data class RuntimeFixtureProjection(
    val loadedVersion: String,
    val activeWorkspaces: List<String>,
    val liveConnections: Int,
)

@Serializable
private data class RuntimeFixtureStatus(
    val type: String,
    val coordinator: RuntimeFixtureCoordinator,
    val projection: RuntimeFixtureProjection,
)
