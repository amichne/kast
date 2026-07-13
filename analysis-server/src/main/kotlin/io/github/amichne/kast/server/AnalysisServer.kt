package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.AnalysisBackend
import io.github.amichne.kast.api.contract.AnalysisTransport
import io.github.amichne.kast.api.client.ServerInstanceDescriptor
import io.github.amichne.kast.api.client.defaultDescriptorDirectory
import kotlinx.coroutines.runBlocking

class AnalysisServer(
    private val backend: AnalysisBackend,
    private val config: AnalysisServerConfig,
    private val lifecycleController: RuntimeLifecycleController = RuntimeLifecycleController.Unavailable,
) {
    fun start(): RunningAnalysisServer {
        val capabilities = runBlocking {
            backend.capabilities()
        }
        val dispatcher = RpcAnalysisDispatcher(backend, config, lifecycleController)

        val transportServer: LocalRpcServer
        val descriptor: ServerInstanceDescriptor?
        val descriptorStore: DescriptorStore?

        when (val transport = config.transport) {
            is AnalysisTransport.UnixDomainSocket -> {
                val socketPath = transport.socketPath.toAbsolutePath().normalize()
                transportServer = UnixDomainSocketRpcServer(
                    socketPath = socketPath,
                    dispatcher = dispatcher,
                ).start()
                descriptor = ServerInstanceDescriptor(
                    workspaceRoot = capabilities.workspaceRoot,
                    backendName = capabilities.backendName,
                    backendVersion = capabilities.backendVersion,
                    socketPath = socketPath.toString(),
                )
                descriptorStore = DescriptorStore(
                    (config.descriptorDirectory ?: defaultDescriptorDirectory())
                        .resolve("daemons.json")
                        .toAbsolutePath()
                        .toString(),
                )
                descriptorStore.write(descriptor)
            }

            AnalysisTransport.Stdio -> {
                transportServer = StdioRpcServer(dispatcher).start()
                descriptor = null
                descriptorStore = null
            }

            is AnalysisTransport.Tcp -> {
                transportServer = TcpRpcServer(
                    host = transport.host,
                    port = transport.port,
                    dispatcher = dispatcher,
                ).start()
                descriptor = null
                descriptorStore = null
            }
        }

        return RunningAnalysisServer(
            server = transportServer,
            dispatcher = dispatcher,
            descriptor = descriptor,
            descriptorStore = descriptorStore,
        )
    }
}
