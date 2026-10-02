@file:Suppress("MagicNumber") // WebSocket frame constants and fixed protocol schema versions.

package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.HostedServiceStatus
import io.github.amichne.kast.distribution.contract.INSTALLATION_MANIFEST_SCHEMA_VERSION
import java.io.DataInputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonIgnoreUnknownKeys

private const val EXCHANGE_DEADLINE_MILLIS = 1500L
private const val MAXIMUM_FRAME_BYTES = 16384
private const val MAXIMUM_HEADER_BYTES = 8192
private const val SOCKET_PATH_LIMIT_BYTES = 104
private const val WEBSOCKET_ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
private val observationJson = Json { encodeDefaults = true }

@Serializable
private data class RuntimeStatusRequest(val type: RuntimeRequestType = RuntimeRequestType.STATUS, val version: Int = 1)

@Serializable
private enum class RuntimeRequestType {
    @SerialName("status") STATUS
}

@Serializable
private enum class RuntimeResponseType {
    @SerialName("status") STATUS
}

@Serializable
private data class RuntimeStatusResponse(
    val type: RuntimeResponseType,
    val coordinator: RuntimeCoordinator,
    val projection: RuntimeProjection? = null,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonIgnoreUnknownKeys
private data class RuntimeCoordinator(
    val installationId: String,
    val stateEpoch: String,
    val serviceGeneration: String,
    val configurationIdentity: String,
)

@Serializable
private data class RuntimeProjection(
    val loadedVersion: String?,
    val activeWorkspaces: List<String>,
    val liveConnections: Int?,
    val hostedServices: List<HostedServiceStatus>? = null,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonIgnoreUnknownKeys
private data class RuntimeEpoch(val schemaVersion: Int, val installation: String, val epoch: String)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonIgnoreUnknownKeys
private data class RuntimeInstallDocument(val schemaVersion: Int, val installationRoot: String, val codexHome: String)

@Serializable
private enum class ReadinessType {
    @SerialName("ready") READY
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonIgnoreUnknownKeys
private data class RuntimeReadiness(
    val state: ReadinessType,
    val schemaVersion: Int,
    val serviceInstanceId: String,
)

@Serializable
internal enum class RuntimeObservationFailure {
    EPOCH_UNAVAILABLE,
    EPOCH_REJECTED,
    MANIFEST_UNAVAILABLE,
    MANIFEST_REJECTED,
    READINESS_UNAVAILABLE,
    READINESS_REJECTED,
    SOCKET_UNAVAILABLE,
    EXCHANGE_UNAVAILABLE,
    RESPONSE_REJECTED,
    IDENTITY_MISMATCH,
    PROJECTION_UNAVAILABLE,
    PROJECTION_REJECTED,
}

internal sealed interface PassiveRuntimeObservation {
    val loadedVersion: Observation<String>
    val activeWorkspaces: Observation<List<String>>
    val liveConnections: Observation<Int>
    val hostedServices: Observation<List<HostedServiceStatus>>

    data class Observed(
        val generation: RuntimeServiceGeneration,
        override val loadedVersion: Observation<String>,
        override val activeWorkspaces: Observation<List<String>>,
        override val liveConnections: Observation<Int>,
        override val hostedServices: Observation<List<HostedServiceStatus>>,
    ) : PassiveRuntimeObservation

    data class Rejected(val failure: RuntimeObservationFailure) : PassiveRuntimeObservation {
        override val loadedVersion: Observation<String>
            get() = Observation.unavailable(failure.name)

        override val activeWorkspaces: Observation<List<String>>
            get() = Observation.unavailable(failure.name)

        override val liveConnections: Observation<Int>
            get() = Observation.unavailable(failure.name)

        override val hostedServices: Observation<List<HostedServiceStatus>>
            get() = Observation.unavailable(failure.name)
    }
}

@Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod")
internal fun observeRuntime(
    installation: Path,
    exchange: (Path, String) -> String? = ::boundedExchange,
): PassiveRuntimeObservation {
    val epochRaw =
        readBoundedFile(installation.resolve("state/epoch.json"), MAXIMUM_HEADER_BYTES.toLong())
            ?: return PassiveRuntimeObservation.Rejected(RuntimeObservationFailure.EPOCH_UNAVAILABLE)
    val epoch =
        try {
            observationJson.decodeFromString<RuntimeEpoch>(epochRaw)
        } catch (_: SerializationException) {
            return PassiveRuntimeObservation.Rejected(RuntimeObservationFailure.EPOCH_REJECTED)
        }
    if (
        epoch.schemaVersion != 1 ||
            !epoch.installation.startsWith("sha256:") ||
            !validSha256(epoch.installation.removePrefix("sha256:"))
    )
        return PassiveRuntimeObservation.Rejected(RuntimeObservationFailure.EPOCH_REJECTED)
    val manifestRaw =
        readBoundedFile(installation.resolve("installation.json"), 67_108_864)
            ?: return PassiveRuntimeObservation.Rejected(RuntimeObservationFailure.MANIFEST_UNAVAILABLE)
    val manifest =
        try {
            observationJson.decodeFromString<RuntimeInstallDocument>(manifestRaw)
        } catch (_: SerializationException) {
            return PassiveRuntimeObservation.Rejected(RuntimeObservationFailure.MANIFEST_REJECTED)
        }
    if (
        manifest.schemaVersion != INSTALLATION_MANIFEST_SCHEMA_VERSION ||
            manifest.installationRoot != installation.toString()
    )
        return PassiveRuntimeObservation.Rejected(RuntimeObservationFailure.MANIFEST_REJECTED)
    val hostHash =
        MessageDigest.getInstance("SHA-256")
            .digest(manifest.codexHome.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
    val readinessRaw =
        readBoundedFile(
            installation.resolve("state/broker/$hostHash/service-readiness.json"),
            MAXIMUM_HEADER_BYTES.toLong(),
        ) ?: return PassiveRuntimeObservation.Rejected(RuntimeObservationFailure.READINESS_UNAVAILABLE)
    val readiness =
        try {
            observationJson.decodeFromString<RuntimeReadiness>(readinessRaw)
        } catch (_: SerializationException) {
            return PassiveRuntimeObservation.Rejected(RuntimeObservationFailure.READINESS_REJECTED)
        }
    if (readiness.schemaVersion != 3)
        return PassiveRuntimeObservation.Rejected(RuntimeObservationFailure.READINESS_REJECTED)
    val generation =
        when (val admission = RuntimeServiceGeneration.admit(readiness.serviceInstanceId)) {
            is RuntimeGenerationAdmission.Admitted -> admission.generation
            RuntimeGenerationAdmission.Rejected ->
                return PassiveRuntimeObservation.Rejected(RuntimeObservationFailure.READINESS_REJECTED)
        }
    val socket =
        runtimeSocket(installation)
            ?: return PassiveRuntimeObservation.Rejected(RuntimeObservationFailure.SOCKET_UNAVAILABLE)
    val response =
        exchange(socket, runtimeStatusRequestDocument())
            ?: return PassiveRuntimeObservation.Rejected(RuntimeObservationFailure.EXCHANGE_UNAVAILABLE)
    val status =
        try {
            observationJson.decodeFromString<RuntimeStatusResponse>(response)
        } catch (_: SerializationException) {
            return PassiveRuntimeObservation.Rejected(RuntimeObservationFailure.RESPONSE_REJECTED)
        }
    if (
        status.coordinator.installationId != epoch.installation ||
            status.coordinator.stateEpoch != epoch.epoch ||
            status.coordinator.serviceGeneration != readiness.serviceInstanceId
    )
        return PassiveRuntimeObservation.Rejected(RuntimeObservationFailure.IDENTITY_MISMATCH)
    val projection =
        status.projection ?: return PassiveRuntimeObservation.Rejected(RuntimeObservationFailure.PROJECTION_UNAVAILABLE)
    if (
        projection.activeWorkspaces.size > 256 ||
            projection.activeWorkspaces.any {
                try {
                    !Path.of(it).isAbsolute
                } catch (_: IllegalArgumentException) {
                    true
                }
            }
    )
        return PassiveRuntimeObservation.Rejected(RuntimeObservationFailure.PROJECTION_REJECTED)
    val loaded = projection.loadedVersion?.takeIf { Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(it) }
    return PassiveRuntimeObservation.Observed(
        generation,
        loaded?.let(Observation.Companion::verified) ?: Observation.unavailable("loaded_version_unavailable"),
        Observation.verified(projection.activeWorkspaces),
        projection.liveConnections?.takeIf { it >= 0 }?.let(Observation.Companion::verified)
            ?: Observation.unavailable("connection_count_unavailable"),
        projectHostedServices(projection.hostedServices),
    )
}

internal fun runtimeStatusRequestDocument(): String = observationJson.encodeToString(RuntimeStatusRequest())

/** Recheck the management DTO boundary before exposing live compatibility to the command user. */
internal fun projectHostedServices(hosts: List<HostedServiceStatus>?): Observation<List<HostedServiceStatus>> {
    if (hosts.isNullOrEmpty()) return Observation.unavailable("host_evidence_unavailable")
    if (hosts.size > 256 || hosts.any { !validHostedServiceStatus(it) })
        return Observation.unavailable("host_evidence_rejected")
    return Observation.verified(hosts)
}

private fun validObservedPath(raw: String): Boolean =
    try {
        val root = Path.of(raw)
        root.isAbsolute && root.normalize() == root
    } catch (_: IllegalArgumentException) {
        false
    }

private fun validHostedServiceStatus(status: HostedServiceStatus): Boolean =
    when (status) {
        is HostedServiceStatus.Compatible ->
            validObservedPath(status.root) &&
                validHostIdentity(status.host, status.hostPid) &&
                validHostedPluginVersion(status.hostedPluginVersion)
        is HostedServiceStatus.Incompatible ->
            validObservedPath(status.root) &&
                validHostIdentity(status.host, status.hostPid) &&
                validHostedPluginVersion(status.hostedPluginVersion)
        is HostedServiceStatus.Unavailable -> validObservedPath(status.root)
        is HostedServiceStatus.RegistryUnavailable -> validObservedPath(status.registryPath)
    }

private fun validHostedPluginVersion(version: String): Boolean =
    Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9]+-g[0-9a-f]{7,40})?").matches(version)

private fun validHostIdentity(host: String, pid: Long): Boolean =
    pid > 0 &&
        try {
            java.util.UUID.fromString(host).toString() == host
        } catch (_: IllegalArgumentException) {
            false
        }

private fun runtimeSocket(installation: Path): Path? {
    val physical = installation.resolve("state/run/c.sock")
    if (physical.toString().toByteArray().size < SOCKET_PATH_LIMIT_BYTES) return physical
    val directory = physical.parent
    val hash =
        MessageDigest.getInstance("SHA-256")
            .digest(directory.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
    val alias = Path.of("/tmp/kast-uds-$hash")
    return try {
        if (Files.isSymbolicLink(alias) && Files.readSymbolicLink(alias) == directory) alias.resolve("c.sock") else null
    } catch (_: Exception) {
        null
    }
}

internal fun boundedExchange(socket: Path, request: String): String? {
    val channel = AtomicReference<SocketChannel?>()
    val executor = Executors.newSingleThreadExecutor()
    return try {
        val result =
            executor.submit<String?> {
                if (!Files.exists(socket, LinkOption.NOFOLLOW_LINKS)) return@submit null
                SocketChannel.open(StandardProtocolFamily.UNIX).use {
                    channel.set(it)
                    it.connect(UnixDomainSocketAddress.of(socket))
                    websocketExchange(it, request)
                }
            }
        try {
            result.get(EXCHANGE_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            result.cancel(true)
            null
        }
    } finally {
        try {
            channel.get()?.close()
        } catch (_: Exception) {
            /* deadline closes the owned socket */
        }
        executor.shutdownNow()
    }
}

private fun websocketExchange(channel: SocketChannel, request: String): String? {
    val input = DataInputStream(Channels.newInputStream(channel))
    val output = Channels.newOutputStream(channel)
    val keyBytes = ByteArray(16).also(SecureRandom()::nextBytes)
    val key = Base64.getEncoder().encodeToString(keyBytes)
    val handshake =
        "GET /kast-management HTTP/1.1\r\nHost: localhost\r\n" +
            "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n" +
            "Sec-WebSocket-Key: $key\r\n\r\n"
    output.write(handshake.toByteArray())
    output.flush()
    val header = readHeader(input) ?: return null
    val accept =
        Base64.getEncoder()
            .encodeToString(MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_ACCEPT_GUID).toByteArray()))
    if (!header.startsWith("HTTP/1.1 101") || !header.contains("Sec-WebSocket-Accept: $accept", ignoreCase = true))
        return null
    val payload = request.toByteArray()
    if (payload.size > MAXIMUM_FRAME_BYTES) return null
    val mask = ByteArray(4).also(SecureRandom()::nextBytes)
    val frame = ByteBuffer.allocate(payload.size + 8)
    frame.put(0x81.toByte())
    if (payload.size < 126) frame.put((0x80 or payload.size).toByte())
    else {
        frame.put(0xfe.toByte())
        frame.putShort(payload.size.toShort())
    }
    frame.put(mask)
    payload.forEachIndexed { index, byte -> frame.put((byte.toInt() xor mask[index % 4].toInt()).toByte()) }
    output.write(frame.array(), 0, frame.position())
    output.flush()
    val first = input.readUnsignedByte()
    if (first != 0x81) return null
    val second = input.readUnsignedByte()
    if (second and 0x80 != 0) return null
    val size =
        when (second) {
            in 0..125 -> second
            126 -> input.readUnsignedShort()
            else -> return null
        }
    if (size > MAXIMUM_FRAME_BYTES) return null
    return ByteArray(size).also(input::readFully).decodeToString()
}

private fun readHeader(input: DataInputStream): String? {
    val bytes = ArrayList<Byte>()
    while (bytes.size < MAXIMUM_HEADER_BYTES) {
        bytes += input.readByte()
        if (bytes.size >= 4 && bytes.takeLast(4) == listOf(13.toByte(), 10.toByte(), 13.toByte(), 10.toByte()))
            return bytes.toByteArray().decodeToString()
    }
    return null
}
