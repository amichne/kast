package io.github.amichne.kast.server

import io.github.amichne.kast.api.AnalysisBackend
import io.github.amichne.kast.api.AnalysisTransport
import io.github.amichne.kast.api.ServerInstanceDescriptor
import io.github.amichne.kast.api.defaultDescriptorDirectory
import kotlinx.coroutines.runBlocking
import java.io.Closeable

class AnalysisServer(
    private val backend: AnalysisBackend,
    private val config: AnalysisServerConfig,
) {
    fun start(): RunningAnalysisServer {
        val capabilities = runBlocking {
            backend.capabilities()
        }
        val dispatcher = AnalysisDispatcher(backend, config)

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
                    (config.descriptorDirectory ?: defaultDescriptorDirectory()).resolve("daemons.json"),
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
            descriptor = descriptor,
            descriptorStore = descriptorStore,
        )
    }
}

class RunningAnalysisServer internal constructor(
    private val server: LocalRpcServer,
    val descriptor: ServerInstanceDescriptor?,
    private val descriptorStore: DescriptorStore?,
) : Closeable {
    fun await() {
        server.await()
    }

    override fun close() {
        descriptorStore?.let { store ->
            descriptor?.let(store::delete)
        }
        server.close()
    }
}
