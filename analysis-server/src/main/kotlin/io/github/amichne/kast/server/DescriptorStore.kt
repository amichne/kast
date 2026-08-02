package io.github.amichne.kast.server

import io.github.amichne.kast.api.client.DescriptorRegistry
import io.github.amichne.kast.api.client.DescriptorRegistryPath
import io.github.amichne.kast.api.client.IndexerBackendName
import io.github.amichne.kast.api.client.RuntimeInstanceId
import io.github.amichne.kast.api.client.RuntimeProcessIdentity
import io.github.amichne.kast.api.client.RuntimeSocketPath
import io.github.amichne.kast.api.client.RuntimeWorkspaceRoot
import io.github.amichne.kast.api.client.ServerInstanceDescriptor
import io.github.amichne.kast.api.client.ServerInstanceOwnership
import io.github.amichne.kast.api.client.SocketOwnerUid
import io.github.amichne.kast.api.contract.compatibility.RuntimeImplementationVersion
import io.github.amichne.kast.api.io.KastFileOperations
import io.github.amichne.kast.api.io.LocalDiskFileOperations
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

@JvmInline
internal value class EffectiveProcessOwnerUid private constructor(val value: Long) {
    companion object {
        fun of(value: Long): EffectiveProcessOwnerUid {
            require(value >= 0) { "Effective process owner UID must be non-negative" }
            return EffectiveProcessOwnerUid(value)
        }
    }
}

internal data class EndpointLaunchRequest(
    val workspaceRoot: RuntimeWorkspaceRoot,
    val backendName: IndexerBackendName,
    val backendVersion: RuntimeImplementationVersion,
    val socketPath: RuntimeSocketPath,
    val runtimeInstanceId: RuntimeInstanceId,
    val processIdentity: RuntimeProcessIdentity,
    val effectiveProcessOwnerUid: EffectiveProcessOwnerUid,
)

internal data class BoundEndpoint<out T : LocalRpcServer>(
    val server: T,
    val evidence: BoundSocketEvidence,
)

internal data class LaunchedEndpoint<out T : LocalRpcServer>(
    val server: T,
    val descriptor: ServerInstanceDescriptor,
)

class DescriptorStore(
    descriptorRegistryPath: DescriptorRegistryPath,
    private val fileOps: KastFileOperations = LocalDiskFileOperations,
) {
    private val daemonsPath = descriptorRegistryPath.toPath()
    private val registry = DescriptorRegistry(descriptorRegistryPath, fileOps)

    fun write(descriptor: ServerInstanceDescriptor) {
        registry.register(descriptor)
    }

    fun delete(descriptor: ServerInstanceDescriptor) {
        registry.delete(descriptor)
    }

    internal fun <T : LocalRpcServer> launchEndpoint(
        request: EndpointLaunchRequest,
        bindEndpoint: () -> BoundEndpoint<T>,
    ): LaunchedEndpoint<T> = withEndpointLaunchLock(request.socketPath) {
        check(daemonsPath != request.socketPath.toPath()) {
            "Runtime descriptor registry and endpoint must use distinct lock targets"
        }
        prepareEndpointForBind(request)
        val bound = bindEndpoint()
        try {
            val actualEvidence = readBoundSocketEvidence(request.socketPath.toPath())
            check(actualEvidence == bound.evidence) {
                "Runtime endpoint changed before descriptor registration"
            }
            check(actualEvidence.socketOwnerUid.isOwnedBy(request.effectiveProcessOwnerUid)) {
                "Runtime endpoint UID does not match the effective process owner"
            }
            val descriptor = ServerInstanceDescriptor(
                workspaceRoot = request.workspaceRoot,
                backendName = request.backendName,
                backendVersion = request.backendVersion,
                socketPath = request.socketPath,
                ownership = ServerInstanceOwnership.Owned(
                    runtimeInstanceId = request.runtimeInstanceId,
                    processIdentity = request.processIdentity,
                    ownerUid = actualEvidence.socketOwnerUid,
                    socketFileIdentity = actualEvidence.socketFileIdentity,
                ),
            )
            registry.register(descriptor)
            LaunchedEndpoint(server = bound.server, descriptor = descriptor)
        } catch (launchFailure: Throwable) {
            try {
                bound.server.close()
            } catch (cleanupFailure: Throwable) {
                launchFailure.addSuppressed(cleanupFailure)
            }
            throw launchFailure
        }
    }

    private fun prepareEndpointForBind(request: EndpointLaunchRequest) {
        val endpointPath = request.socketPath.toPath()
        check(!Files.isSymbolicLink(daemonsPath)) {
            "Runtime descriptor registry must not be a symbolic link: $daemonsPath"
        }
        if (!Files.exists(endpointPath, LinkOption.NOFOLLOW_LINKS)) return
        check(!endpointReachable(request.socketPath)) {
            "Runtime endpoint is already reachable: ${request.socketPath}"
        }
        val first = matchingDescriptor(request.socketPath)
        check(first.workspaceRoot == request.workspaceRoot) { "Stale endpoint workspace ownership does not match" }
        check(first.backendName == request.backendName) { "Stale endpoint backend ownership does not match" }
        val ownership = checkNotNull(first.ownership as? ServerInstanceOwnership.Owned) {
            "Legacy descriptor cannot authorize endpoint removal"
        }
        check(ownership.ownerUid.isOwnedBy(request.effectiveProcessOwnerUid)) {
            "Stale endpoint UID ownership does not match"
        }
        check(recordedProcessIsGone(ownership.processIdentity)) {
            "Descriptor process identity is still alive"
        }
        val recordedEvidence = BoundSocketEvidence(
            socketFileIdentity = ownership.socketFileIdentity,
            socketOwnerUid = ownership.ownerUid,
        )
        check(readBoundSocketEvidence(endpointPath) == recordedEvidence) {
            "Stale endpoint inode ownership does not match"
        }

        val second = matchingDescriptor(request.socketPath)
        check(second == first) { "Runtime descriptor changed during stale endpoint validation" }
        check(readBoundSocketEvidence(endpointPath) == recordedEvidence) {
            "Runtime endpoint changed during stale endpoint validation"
        }
        Files.delete(endpointPath)
        registry.delete(first)
    }

    private fun matchingDescriptor(socketPath: RuntimeSocketPath): ServerInstanceDescriptor {
        val matches = registry.descriptors().filter { descriptor ->
            descriptor.socketPath == socketPath
        }
        check(matches.size == 1) { "Expected one descriptor for stale endpoint $socketPath, found ${matches.size}" }
        return matches.single()
    }

    private fun endpointReachable(socketPath: RuntimeSocketPath): Boolean = runCatching {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(socketPath.toPath()))
        }
    }.isSuccess

    private fun recordedProcessIsGone(identity: RuntimeProcessIdentity): Boolean {
        val process = ProcessHandle.of(identity.processId.value).orElse(null) ?: return true
        if (!process.isAlive) return true
        val actualStart = process.info().startInstant().orElse(null) ?: return false
        return actualStart.toEpochMilli() != identity.processStartEpochMillis.value
    }

    private fun <T> withEndpointLaunchLock(socketPath: RuntimeSocketPath, block: () -> T): T {
        val guard = endpointLaunchGuards.computeIfAbsent(socketPath.value) { Any() }
        return synchronized(guard) {
            fileOps.withLock(socketPath.value, block)
        }
    }

    private companion object {
        val endpointLaunchGuards = ConcurrentHashMap<String, Any>()
    }
}

internal fun SocketOwnerUid.isOwnedBy(effectiveOwnerUid: EffectiveProcessOwnerUid): Boolean =
    value == effectiveOwnerUid.value

internal fun readEffectiveProcessOwnerUid(probeDirectory: Path): EffectiveProcessOwnerUid {
    val probe = Files.createTempFile(probeDirectory, ".kast-owner-probe-", ".tmp")
    return try {
        EffectiveProcessOwnerUid.of(
            (Files.getAttribute(probe, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Number).toLong(),
        )
    } finally {
        Files.deleteIfExists(probe)
    }
}
