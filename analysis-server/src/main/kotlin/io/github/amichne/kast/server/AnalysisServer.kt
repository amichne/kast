package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.CloseableAnalysisBackend
import io.github.amichne.kast.api.contract.AnalysisTransport
import io.github.amichne.kast.api.client.ServerInstanceDescriptor
import io.github.amichne.kast.api.client.defaultDescriptorDirectory
import kotlinx.coroutines.runBlocking

class AnalysisServer(
    private val backend: CloseableAnalysisBackend,
    private val config: AnalysisServerConfig,
    private val lifecycleController: RuntimeLifecycleController = RuntimeLifecycleController.Unavailable,
    private val projectOpenController: RuntimeProjectOpenController = RuntimeProjectOpenController.Unavailable,
) {
    fun start(): RunningAnalysisServer {
        val capabilities = runBlocking {
            backend.capabilities()
        }
        val dispatcher = RpcAnalysisDispatcher(
            backend,
            config,
            lifecycleController,
            projectOpenController,
        )
        var transportServer: LocalRpcServer? = null
        var descriptor: ServerInstanceDescriptor? = null
        var descriptorStore: DescriptorStore? = null

        try {
            when (val transport = config.transport) {
                is AnalysisTransport.UnixDomainSocket -> {
                    val socketPath = transport.socketPath.toAbsolutePath().normalize()
                    transportServer = UnixDomainSocketRpcServer(
                        socketPath = socketPath,
                        dispatcher = dispatcher,
                    ).start()
                    val startedDescriptor = ServerInstanceDescriptor(
                        workspaceRoot = capabilities.workspaceRoot,
                        backendName = capabilities.backendName,
                        backendVersion = capabilities.backendVersion,
                        socketPath = socketPath.toString(),
                    )
                    descriptor = startedDescriptor
                    val startedDescriptorStore = DescriptorStore(
                        (config.descriptorDirectory ?: defaultDescriptorDirectory())
                            .resolve("daemons.json")
                            .toAbsolutePath()
                            .toString(),
                    )
                    descriptorStore = startedDescriptorStore
                    startedDescriptorStore.write(startedDescriptor)
                }

                AnalysisTransport.Stdio -> {
                    transportServer = StdioRpcServer(dispatcher).start()
                }

                is AnalysisTransport.Tcp -> {
                    transportServer = TcpRpcServer(
                        host = transport.host,
                        port = transport.port,
                        dispatcher = dispatcher,
                    ).start()
                }
            }

            return RunningAnalysisServer(
                server = checkNotNull(transportServer),
                dispatcher = dispatcher,
                backend = backend,
                descriptor = descriptor,
                descriptorStore = descriptorStore,
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
