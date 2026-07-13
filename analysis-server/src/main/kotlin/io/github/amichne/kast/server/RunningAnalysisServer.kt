package io.github.amichne.kast.server

import io.github.amichne.kast.api.client.ServerInstanceDescriptor
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class RunningAnalysisServer internal constructor(
    private val server: LocalRpcServer,
    private val dispatcher: RpcAnalysisDispatcher,
    val descriptor: ServerInstanceDescriptor?,
    private val descriptorStore: DescriptorStore?,
) : Closeable {
    private val closed = AtomicBoolean(false)

    fun await() {
        server.await()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        server.close()
        dispatcher.close()
        descriptorStore?.let { store ->
            descriptor?.let(store::delete)
        }
    }
}
