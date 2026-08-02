package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.contract.compatibility.RuntimeImplementationVersion
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = SocketFileIdentity.Serializer::class)
data class SocketFileIdentity(
    val device: Long,
    val inode: Long,
) {
    init {
        require(device >= 0) { "Socket device must be non-negative" }
        require(inode > 0) { "Socket inode must be positive" }
    }

    companion object {
        fun of(device: Long, inode: Long): SocketFileIdentity = SocketFileIdentity(device = device, inode = inode)
    }

    object Serializer : KSerializer<SocketFileIdentity> {
        private val delegate = SocketFileIdentityWire.serializer()

        override val descriptor: SerialDescriptor = delegate.descriptor

        override fun serialize(encoder: Encoder, value: SocketFileIdentity) {
            delegate.serialize(encoder, SocketFileIdentityWire(value.device, value.inode))
        }

        override fun deserialize(decoder: Decoder): SocketFileIdentity {
            val wire = delegate.deserialize(decoder)
            return of(device = wire.device, inode = wire.inode)
        }
    }
}

data class RuntimeProcessIdentity(
    val processId: ProcessId,
    val processStartEpochMillis: ProcessStartEpochMillis,
)

sealed interface ServerInstanceOwnership {
    val processId: ProcessId?

    data object LegacyWithoutProcessId : ServerInstanceOwnership {
        override val processId: ProcessId? = null
    }

    data class Legacy(
        override val processId: ProcessId,
    ) : ServerInstanceOwnership

    data class Owned(
        val runtimeInstanceId: RuntimeInstanceId,
        val processIdentity: RuntimeProcessIdentity,
        val ownerUid: SocketOwnerUid,
        val socketFileIdentity: SocketFileIdentity,
    ) : ServerInstanceOwnership {
        override val processId: ProcessId
            get() = processIdentity.processId
    }
}

@Serializable(with = ServerInstanceDescriptor.Serializer::class)
data class ServerInstanceDescriptor(
    val workspaceRoot: RuntimeWorkspaceRoot,
    val backendName: HeadlessBackendName = HeadlessBackendName.HEADLESS,
    val backendVersion: RuntimeImplementationVersion,
    val transport: UnixDomainSocketTransport = UnixDomainSocketTransport.UDS,
    val socketPath: RuntimeSocketPath,
    val ownership: ServerInstanceOwnership,
    val schemaVersion: DescriptorSchemaVersion = DescriptorSchemaVersion.CURRENT,
) {
    object Serializer : KSerializer<ServerInstanceDescriptor> {
        private val delegate = ServerInstanceDescriptorWire.serializer()

        override val descriptor: SerialDescriptor = delegate.descriptor

        override fun serialize(encoder: Encoder, value: ServerInstanceDescriptor) {
            delegate.serialize(encoder, value.toWire())
        }

        override fun deserialize(decoder: Decoder): ServerInstanceDescriptor =
            delegate.deserialize(decoder).toDomain()
    }
}

@Serializable
@SerialName("io.github.amichne.kast.api.client.SocketFileIdentity")
private data class SocketFileIdentityWire(
    val device: Long,
    val inode: Long,
)

@Serializable
@SerialName("io.github.amichne.kast.api.client.ServerInstanceDescriptor")
private data class ServerInstanceDescriptorWire(
    val workspaceRoot: RuntimeWorkspaceRoot,
    val backendName: HeadlessBackendName,
    val backendVersion: RuntimeImplementationVersion,
    val transport: UnixDomainSocketTransport = UnixDomainSocketTransport.UDS,
    val socketPath: RuntimeSocketPath,
    val pid: ProcessId? = null,
    val runtimeInstanceId: RuntimeInstanceId? = null,
    val processStartEpochMillis: ProcessStartEpochMillis? = null,
    val ownerUid: SocketOwnerUid? = null,
    val socketFileIdentity: SocketFileIdentity? = null,
    val schemaVersion: DescriptorSchemaVersion = DescriptorSchemaVersion.CURRENT,
)

private fun ServerInstanceDescriptor.toWire(): ServerInstanceDescriptorWire = when (val owner = ownership) {
    ServerInstanceOwnership.LegacyWithoutProcessId -> ServerInstanceDescriptorWire(
        workspaceRoot = workspaceRoot,
        backendName = backendName,
        backendVersion = backendVersion,
        transport = transport,
        socketPath = socketPath,
        schemaVersion = schemaVersion,
    )

    is ServerInstanceOwnership.Legacy -> ServerInstanceDescriptorWire(
        workspaceRoot = workspaceRoot,
        backendName = backendName,
        backendVersion = backendVersion,
        transport = transport,
        socketPath = socketPath,
        pid = owner.processId,
        schemaVersion = schemaVersion,
    )

    is ServerInstanceOwnership.Owned -> ServerInstanceDescriptorWire(
        workspaceRoot = workspaceRoot,
        backendName = backendName,
        backendVersion = backendVersion,
        transport = transport,
        socketPath = socketPath,
        pid = owner.processIdentity.processId,
        runtimeInstanceId = owner.runtimeInstanceId,
        processStartEpochMillis = owner.processIdentity.processStartEpochMillis,
        ownerUid = owner.ownerUid,
        socketFileIdentity = owner.socketFileIdentity,
        schemaVersion = schemaVersion,
    )
}

private fun ServerInstanceDescriptorWire.toDomain(): ServerInstanceDescriptor {
    val ownershipFields = listOf(runtimeInstanceId, processStartEpochMillis, ownerUid, socketFileIdentity)
    val ownership = when {
        ownershipFields.all { it == null } && pid == null -> ServerInstanceOwnership.LegacyWithoutProcessId
        ownershipFields.all { it == null } -> ServerInstanceOwnership.Legacy(requireNotNull(pid))
        ownershipFields.all { it != null } && pid != null -> ServerInstanceOwnership.Owned(
            runtimeInstanceId = requireNotNull(runtimeInstanceId),
            processIdentity = RuntimeProcessIdentity(
                processId = requireNotNull(pid),
                processStartEpochMillis = requireNotNull(processStartEpochMillis),
            ),
            ownerUid = requireNotNull(ownerUid),
            socketFileIdentity = requireNotNull(socketFileIdentity),
        )

        else -> throw SerializationException(
            "Runtime ownership fields must be either complete or absent for a legacy descriptor",
        )
    }
    return ServerInstanceDescriptor(
        workspaceRoot = workspaceRoot,
        backendName = backendName,
        backendVersion = backendVersion,
        transport = transport,
        socketPath = socketPath,
        ownership = ownership,
        schemaVersion = schemaVersion,
    )
}
