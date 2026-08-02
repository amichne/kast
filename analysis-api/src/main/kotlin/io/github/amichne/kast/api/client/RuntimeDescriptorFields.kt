package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = RuntimeInstanceId.Serializer::class)
@JvmInline
value class RuntimeInstanceId private constructor(val value: String) {
    companion object {
        fun create(): RuntimeInstanceId = RuntimeInstanceId(UUID.randomUUID().toString())

        fun parse(value: String): RuntimeInstanceId {
            val parsed = UUID.fromString(value)
            require(parsed.toString() == value) { "Runtime instance ID must be a canonical UUID" }
            return RuntimeInstanceId(value)
        }
    }

    object Serializer : KSerializer<RuntimeInstanceId> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("RuntimeInstanceId", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: RuntimeInstanceId) {
            encoder.encodeString(value.value)
        }

        override fun deserialize(decoder: Decoder): RuntimeInstanceId = parse(decoder.decodeString())
    }
}

@Serializable(with = RuntimeWorkspaceRoot.Serializer::class)
@JvmInline
value class RuntimeWorkspaceRoot private constructor(val value: String) {
    fun toPath(): Path = Path.of(value)

    companion object {
        fun canonicalize(path: Path): RuntimeWorkspaceRoot = RuntimeWorkspaceRoot(NormalizedPath.of(path).value)

        fun parse(value: String): RuntimeWorkspaceRoot =
            RuntimeWorkspaceRoot(requireNormalizedAbsolutePath(value, "Runtime workspace root"))
    }

    object Serializer : KSerializer<RuntimeWorkspaceRoot> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("RuntimeWorkspaceRoot", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: RuntimeWorkspaceRoot) {
            encoder.encodeString(value.value)
        }

        override fun deserialize(decoder: Decoder): RuntimeWorkspaceRoot = parse(decoder.decodeString())
    }
}

@Serializable(with = HeadlessBackendName.Serializer::class)
@JvmInline
value class HeadlessBackendName private constructor(val value: String) {
    companion object {
        val HEADLESS: HeadlessBackendName = HeadlessBackendName("headless")

        fun parse(value: String): HeadlessBackendName {
            require(value == HEADLESS.value) { "Runtime backend must be headless" }
            return HEADLESS
        }
    }

    object Serializer : KSerializer<HeadlessBackendName> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("HeadlessBackendName", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: HeadlessBackendName) {
            encoder.encodeString(value.value)
        }

        override fun deserialize(decoder: Decoder): HeadlessBackendName = parse(decoder.decodeString())
    }
}

@Serializable(with = UnixDomainSocketTransport.Serializer::class)
@JvmInline
value class UnixDomainSocketTransport private constructor(val value: String) {
    companion object {
        val UDS: UnixDomainSocketTransport = UnixDomainSocketTransport("uds")

        fun parse(value: String): UnixDomainSocketTransport {
            require(value == UDS.value) { "Runtime descriptor transport must be uds" }
            return UDS
        }
    }

    object Serializer : KSerializer<UnixDomainSocketTransport> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("UnixDomainSocketTransport", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: UnixDomainSocketTransport) {
            encoder.encodeString(value.value)
        }

        override fun deserialize(decoder: Decoder): UnixDomainSocketTransport = parse(decoder.decodeString())
    }
}

@Serializable(with = RuntimeSocketPath.Serializer::class)
@JvmInline
value class RuntimeSocketPath private constructor(val value: String) {
    fun toPath(): Path = Path.of(value)

    companion object {
        fun of(path: Path): RuntimeSocketPath = RuntimeSocketPath(NormalizedPath.of(path).value)

        fun parse(value: String): RuntimeSocketPath {
            val normalized = requireNormalizedAbsolutePath(value, "Runtime socket path")
            val canonical = NormalizedPath.of(Path.of(normalized)).value
            require(canonical == normalized) { "Runtime socket path must be canonical" }
            return RuntimeSocketPath(canonical)
        }
    }

    object Serializer : KSerializer<RuntimeSocketPath> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("RuntimeSocketPath", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: RuntimeSocketPath) {
            encoder.encodeString(value.value)
        }

        override fun deserialize(decoder: Decoder): RuntimeSocketPath = parse(decoder.decodeString())
    }
}

@Serializable(with = ProcessId.Serializer::class)
@JvmInline
value class ProcessId private constructor(val value: Long) {
    companion object {
        fun current(): ProcessId = of(ProcessHandle.current().pid())

        fun of(value: Long): ProcessId {
            require(value > 0) { "Process ID must be positive" }
            return ProcessId(value)
        }
    }

    object Serializer : KSerializer<ProcessId> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ProcessId", PrimitiveKind.LONG)

        override fun serialize(encoder: Encoder, value: ProcessId) {
            encoder.encodeLong(value.value)
        }

        override fun deserialize(decoder: Decoder): ProcessId = of(decoder.decodeLong())
    }
}

@Serializable(with = DescriptorSchemaVersion.Serializer::class)
@JvmInline
value class DescriptorSchemaVersion private constructor(val value: Int) {
    companion object {
        val CURRENT: DescriptorSchemaVersion = of(SCHEMA_VERSION)

        fun of(value: Int): DescriptorSchemaVersion {
            require(value == SCHEMA_VERSION) {
                "Unsupported descriptor schema version: $value"
            }
            return DescriptorSchemaVersion(value)
        }
    }

    object Serializer : KSerializer<DescriptorSchemaVersion> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("DescriptorSchemaVersion", PrimitiveKind.INT)

        override fun serialize(encoder: Encoder, value: DescriptorSchemaVersion) {
            encoder.encodeInt(value.value)
        }

        override fun deserialize(decoder: Decoder): DescriptorSchemaVersion = of(decoder.decodeInt())
    }
}

@Serializable(with = ProcessStartEpochMillis.Serializer::class)
@JvmInline
value class ProcessStartEpochMillis private constructor(val value: Long) {
    companion object {
        fun of(value: Long): ProcessStartEpochMillis {
            require(value > 0) { "Process start epoch milliseconds must be positive" }
            return ProcessStartEpochMillis(value)
        }
    }

    object Serializer : KSerializer<ProcessStartEpochMillis> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("ProcessStartEpochMillis", PrimitiveKind.LONG)

        override fun serialize(encoder: Encoder, value: ProcessStartEpochMillis) {
            encoder.encodeLong(value.value)
        }

        override fun deserialize(decoder: Decoder): ProcessStartEpochMillis = of(decoder.decodeLong())
    }
}

@Serializable(with = SocketOwnerUid.Serializer::class)
@JvmInline
value class SocketOwnerUid private constructor(val value: Long) {
    companion object {
        fun of(value: Long): SocketOwnerUid {
            require(value >= 0) { "Socket owner UID must be non-negative" }
            return SocketOwnerUid(value)
        }
    }

    object Serializer : KSerializer<SocketOwnerUid> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SocketOwnerUid", PrimitiveKind.LONG)

        override fun serialize(encoder: Encoder, value: SocketOwnerUid) {
            encoder.encodeLong(value.value)
        }

        override fun deserialize(decoder: Decoder): SocketOwnerUid = of(decoder.decodeLong())
    }
}

private fun requireNormalizedAbsolutePath(value: String, fieldName: String): String {
    require(value.isNotBlank()) { "$fieldName must not be blank" }
    require(value.none(Char::isISOControl)) { "$fieldName must not contain control characters" }
    val path = Path.of(value)
    require(path.isAbsolute) { "$fieldName must be absolute" }
    require(path.normalize().toString() == value) { "$fieldName must be normalized" }
    return value
}
