package io.github.amichne.kast.server

import io.github.amichne.kast.api.client.DescriptorRegistryPath
import io.github.amichne.kast.api.client.IndexerBackendName
import io.github.amichne.kast.api.client.ProcessId
import io.github.amichne.kast.api.client.ProcessStartEpochMillis
import io.github.amichne.kast.api.client.RuntimeInstanceId
import io.github.amichne.kast.api.client.RuntimeProcessIdentity
import io.github.amichne.kast.api.client.RuntimeSocketPath
import io.github.amichne.kast.api.client.RuntimeWorkspaceRoot
import io.github.amichne.kast.api.client.ServerInstanceDescriptor
import io.github.amichne.kast.api.client.defaultDescriptorDirectory
import io.github.amichne.kast.api.contract.AnalysisTransport
import io.github.amichne.kast.api.contract.CloseableAnalysisBackend
import io.github.amichne.kast.api.contract.compatibility.RuntimeImplementationVersion
import io.github.amichne.kast.server.change.VerifiedAddDeclarationBinding
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

class AnalysisServer(
    private val backend: CloseableAnalysisBackend,
    private val config: AnalysisServerConfig,
    private val publicSymbolReads: PublicSymbolReadBinding =
        PublicSymbolReadBinding.LegacyAnalysisBackend,
    private val verifiedAddDeclarations: VerifiedAddDeclarationBinding =
        VerifiedAddDeclarationBinding.Unavailable,
) {
    fun start(): RunningAnalysisServer {
        val capabilities = runBlocking {
            backend.capabilities()
        }
        val dispatcher = RpcAnalysisDispatcher(
            backend,
            config,
            publicSymbolReads,
            verifiedAddDeclarations,
        )
        var transportServer: LocalRpcServer? = null
        var descriptor: ServerInstanceDescriptor? = null
        var descriptorStore: DescriptorStore? = null

        try {
            when (val transport = config.transport) {
                is AnalysisTransport.UnixDomainSocket -> {
                    val bindSocketPath = transport.socketPath.toAbsolutePath().normalize()
                    val socketPath = RuntimeSocketPath.of(bindSocketPath)
                    val instanceId = config.runtimeInstanceId ?: RuntimeInstanceId.create()
                    val descriptorDirectory = (config.descriptorDirectory ?: defaultDescriptorDirectory())
                        .toAbsolutePath()
                        .normalize()
                    secureDescriptorDirectory(descriptorDirectory)
                    val startedDescriptorStore = DescriptorStore(
                        DescriptorRegistryPath.of(descriptorDirectory.resolve("daemons.json")),
                    )
                    descriptorStore = startedDescriptorStore
                    val canonicalWorkspaceRoot = RuntimeWorkspaceRoot.canonicalize(Path.of(capabilities.workspaceRoot))
                    val launch = startedDescriptorStore.launchEndpoint(
                        EndpointLaunchRequest(
                            workspaceRoot = canonicalWorkspaceRoot,
                            backendName = IndexerBackendName.INDEXER,
                            backendVersion = RuntimeImplementationVersion(capabilities.backendVersion),
                            socketPath = socketPath,
                            runtimeInstanceId = instanceId,
                            processIdentity = RuntimeProcessIdentity(
                                processId = ProcessId.current(),
                                processStartEpochMillis = ProcessStartEpochMillis.of(
                                    ProcessHandle.current().info().startInstant().orElseThrow {
                                        IllegalStateException("Current process start identity is unavailable")
                                    }.toEpochMilli(),
                                ),
                            ),
                            effectiveProcessOwnerUid = readEffectiveProcessOwnerUid(descriptorDirectory),
                        ),
                    ) {
                        val provisionalServer = UnixDomainSocketRpcServer(
                            socketPath = bindSocketPath,
                            dispatcher = dispatcher,
                        ).start()
                        BoundEndpoint(
                            server = provisionalServer,
                            evidence = provisionalServer.boundSocketEvidence,
                        )
                    }
                    transportServer = launch.server
                    descriptor = launch.descriptor
                }

                AnalysisTransport.Stdio -> {
                    transportServer = StdioRpcServer(dispatcher).start()
                }

                is AnalysisTransport.Tcp -> {
                    val provisionalServer = TcpRpcServer(
                        host = transport.host,
                        port = transport.port,
                        dispatcher = dispatcher,
                    )
                    transportServer = provisionalServer
                    provisionalServer.start()
                }
            }

            return RunningAnalysisServer(
                server = checkNotNull(transportServer),
                dispatcher = dispatcher,
                backend = backend,
                descriptor = descriptor,
                descriptorStore = descriptorStore,
                runtimeCapabilityLeases = config.runtimeCapabilityLeases,
            )
        } catch (startupFailure: Throwable) {
            listOf<() -> Unit>(
                { transportServer?.close() },
                dispatcher::close,
                {
                    descriptorStore?.let { store ->
                        descriptor?.let(store::delete)
                    }
                },
            ).forEach { cleanupPhase ->
                try {
                    cleanupPhase()
                } catch (cleanupFailure: Throwable) {
                    startupFailure.addSuppressed(cleanupFailure)
                }
            }
            throw startupFailure
        }
    }
}

private fun secureDescriptorDirectory(directory: java.nio.file.Path) {
    Files.createDirectories(directory)
    runCatching {
        Files.setPosixFilePermissions(
            directory,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
    }.getOrElse { error ->
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) throw error
    }
}
