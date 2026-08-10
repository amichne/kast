package io.github.amichne.kast.server

import io.github.amichne.kast.api.client.ServerInstanceDescriptor
import io.github.amichne.kast.api.contract.CloseableAnalysisBackend
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import io.github.amichne.kast.api.contract.RuntimeCapabilityLeaseRegistry
import io.github.amichne.kast.api.contract.RuntimeStopPermit
import io.github.amichne.kast.api.contract.RuntimeStopPermitAdmission

class RunningAnalysisServer internal constructor(
    private val server: LocalRpcServer,
    private val dispatcher: Closeable,
    private val backend: CloseableAnalysisBackend,
    val descriptor: ServerInstanceDescriptor?,
    private val descriptorStore: DescriptorStore?,
    private val runtimeCapabilityLeases: RuntimeCapabilityLeaseRegistry? = null,
) : Closeable {
    private val closed = AtomicBoolean(false)

    init {
        runtimeCapabilityLeases?.onStopPermit(::consumeStopPermit)
    }

    private fun consumeStopPermit(permit: RuntimeStopPermit) {
        if (runtimeCapabilityLeases?.admitStop(permit) !is RuntimeStopPermitAdmission.Admitted) return
        val admitted = descriptorStore?.admitStop(permit, descriptor) ?: StopIdentityAdmission.Rejected
        if (admitted is StopIdentityAdmission.Admitted) close()
    }

    fun await() {
        server.await()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }

        var firstFailure: Throwable? = null
        listOf<() -> Unit>(
            server::close,
            dispatcher::close,
            backend::close,
            { runtimeCapabilityLeases?.close() },
            {
                descriptorStore?.let { store ->
                    descriptor?.let(store::delete)
                }
            },
        ).forEach { closePhase ->
            try {
                closePhase()
            } catch (failure: Throwable) {
                if (firstFailure == null) {
                    firstFailure = failure
                } else {
                    firstFailure.addSuppressed(failure)
                }
            }
        }
        firstFailure?.let { failure -> throw failure }
    }
}
