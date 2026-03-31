package io.github.amichne.kast.server

import io.github.amichne.kast.api.AnalysisBackend
import io.github.amichne.kast.api.ServerInstanceDescriptor
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import kotlin.io.path.Path

class AnalysisServer(
    private val backend: AnalysisBackend,
    private val config: AnalysisServerConfig,
) {
    fun start(): RunningAnalysisServer {
        val engine = embeddedServer(
            factory = Netty,
            host = config.host,
            port = config.port,
        ) {
            kastModule(backend, config)
        }
        engine.start(wait = false)

        val connector = runBlocking {
            engine.engine.resolvedConnectors().first()
        }
        val capabilities = runBlocking {
            backend.capabilities()
        }
        val descriptor = ServerInstanceDescriptor(
            workspaceRoot = capabilities.workspaceRoot,
            backendName = capabilities.backendName,
            backendVersion = capabilities.backendVersion,
            host = config.host,
            port = connector.port,
            token = config.token,
        )
        val descriptorStore = DescriptorStore(
            directory = config.descriptorDirectory ?: defaultDescriptorDirectory(Path(capabilities.workspaceRoot)),
        )
        descriptorStore.write(descriptor)

        return RunningAnalysisServer(
            engine = engine,
            descriptor = descriptor,
            descriptorStore = descriptorStore,
        )
    }
}

class RunningAnalysisServer(
    private val engine: EmbeddedServer<*, *>,
    val descriptor: ServerInstanceDescriptor,
    private val descriptorStore: DescriptorStore,
) : Closeable {
    override fun close() {
        descriptorStore.delete(descriptor)
        engine.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
    }
}
