package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/** Connections bound concurrent dispatch; mutation owners retain their own write admission. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun serveHostedListener(
    listener: ServerSocketChannel,
    observer: HostedEndpointObserver,
    limits: ReadLimits,
    dispatch: suspend (HostedRequest, HostedTransportTrace) -> HostedResponse,
) = coroutineScope {
    val connections = Semaphore(limits[ReadLimitParameter.HOST_CONNECTIONS].value)
    while (isActive) {
        val trace = HostedTransportTrace(observer)
        trace.enter(HostedTransportStage.ACCEPT)
        val client =
            when (val accepted = acceptHostedConnection(observer, accept = listener::accept)) {
                is Refinement.Refined -> accepted.value
                is Refinement.Rejected -> {
                    trace.emit(HostedEndpointOutcome.REJECTED, failure = accepted.failure)
                    break
                }
            }
        trace.emit(HostedEndpointOutcome.COMPLETED)
        if (connections.tryAcquire()) {
            // Atomic start installs cleanup even when cancellation races with scheduling.
            launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
                try {
                    client.use {
                        serveAdmittedHostedConnection(it, observer, trace, limits, dispatch)
                    }
                } finally {
                    connections.release()
                    trace.enter(HostedTransportStage.CONNECTION_RELEASE)
                    trace.emit(HostedEndpointOutcome.COMPLETED)
                }
            }
        } else {
            // One bounded rejection exchange, without another queued job or semantic admission.
            client.use {
                serveHostedConnection(
                    Channels.newInputStream(it),
                    Channels.newOutputStream(it),
                    observer,
                    limits,
                    trace,
                ) {
                    trace.enter(HostedTransportStage.SEMANTIC_ADMISSION)
                    trace.emit(
                        HostedEndpointOutcome.REJECTED,
                        failure = HostedEndpointFailure.ADMISSION_CAPACITY_EXCEEDED,
                    )
                    HostedResponse.Rejected(HostedEndpointFailure.ADMISSION_CAPACITY_EXCEEDED)
                }
            }
        }
    }
}

private suspend fun serveAdmittedHostedConnection(
    client: SocketChannel,
    observer: HostedEndpointObserver,
    trace: HostedTransportTrace,
    limits: ReadLimits,
    dispatch: suspend (HostedRequest, HostedTransportTrace) -> HostedResponse,
) {
    serveHostedConnection(Channels.newInputStream(client), Channels.newOutputStream(client), observer, limits, trace) {
        request ->
        when (
            val result =
                dispatchUntilPeerTermination(client::awaitHostedPeerTermination) {
                    dispatchHosted(request, trace, dispatch)
                }
        ) {
            is HostedPeerDispatch.Completed -> result.response
            is HostedPeerDispatch.Rejected ->
                HostedResponse.Rejected(
                    when (result.termination) {
                        HostedPeerTermination.DISCONNECTED -> HostedEndpointFailure.IO_UNAVAILABLE
                        HostedPeerTermination.EXTRA_INPUT -> HostedEndpointFailure.INVALID_REQUEST
                    }
                )
        }
    }
}

private fun io.github.amichne.kast.workspace.intellij.read.hosted.HostedEvaluationOutcome.transportOutcome() =
    when (this) {
        io.github.amichne.kast.workspace.intellij.read.hosted.HostedEvaluationOutcome.EVALUATED,
        io.github.amichne.kast.workspace.intellij.read.hosted.HostedEvaluationOutcome.COMPLETE ->
            HostedEndpointOutcome.COMPLETED
        io.github.amichne.kast.workspace.intellij.read.hosted.HostedEvaluationOutcome.QUALIFIED ->
            HostedEndpointOutcome.QUALIFIED
        io.github.amichne.kast.workspace.intellij.read.hosted.HostedEvaluationOutcome.REJECTED ->
            HostedEndpointOutcome.REJECTED
    }

private suspend fun dispatchHosted(
    request: HostedRequest,
    trace: HostedTransportTrace,
    dispatch: suspend (HostedRequest, HostedTransportTrace) -> HostedResponse,
): HostedResponse {
    trace.enter(HostedTransportStage.SEMANTIC_ADMISSION)
    trace.emit(HostedEndpointOutcome.COMPLETED)
    trace.enter(HostedTransportStage.EXECUTION)
    return dispatch(request, trace).also { trace.emit(it.outcome.transportOutcome()) }
}
