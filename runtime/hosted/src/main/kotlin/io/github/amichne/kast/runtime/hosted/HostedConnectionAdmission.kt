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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

/** Frame I/O is concurrent and bounded; all semantic and mutation dispatch remains serialized. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun serveHostedListener(
    listener: ServerSocketChannel,
    observer: HostedEndpointObserver,
    limits: ReadLimits,
    dispatch: suspend (HostedRequest) -> HostedResponse,
) = coroutineScope {
    val connections = Semaphore(limits[ReadLimitParameter.HOST_CONNECTIONS].value)
    val semantic = Mutex()
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
                        serveAdmittedHostedConnection(it, observer, trace, limits, semantic, dispatch)
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
    semantic: Mutex,
    dispatch: suspend (HostedRequest) -> HostedResponse,
) {
    val acceptedAt = System.nanoTime()
    serveHostedConnection(Channels.newInputStream(client), Channels.newOutputStream(client), observer, limits, trace) {
        request ->
        when (
            val result =
                dispatchUntilPeerTermination(client::awaitHostedPeerTermination) {
                    semantic.dispatchHosted(request, trace, limits, acceptedAt, dispatch)
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

private enum class HostedSemanticAdmission {
    ADMITTED,
    DEADLINE_EXCEEDED,
}

private suspend fun Mutex.dispatchHosted(
    request: HostedRequest,
    trace: HostedTransportTrace,
    limits: ReadLimits,
    acceptedAt: Long,
    dispatch: suspend (HostedRequest) -> HostedResponse,
): HostedResponse {
    trace.enter(HostedTransportStage.SEMANTIC_ADMISSION)
    val elapsedMillis = (System.nanoTime() - acceptedAt) / 1_000_000
    val queueMillis =
        limits[ReadLimitParameter.HOST_CONNECTION_MILLIS].value.toLong() -
            limits[ReadLimitParameter.HOST_QUERY_MILLIS].value -
            elapsedMillis -
            100
    val ownership = Any()
    return try {
        val admission =
            withTimeoutOrNull(queueMillis) {
                lock(ownership)
                HostedSemanticAdmission.ADMITTED
            } ?: HostedSemanticAdmission.DEADLINE_EXCEEDED
        when (admission) {
            HostedSemanticAdmission.DEADLINE_EXCEEDED -> {
                trace.emit(
                    HostedEndpointOutcome.REJECTED,
                    failure = HostedEndpointFailure.ADMISSION_DEADLINE_EXCEEDED,
                )
                HostedResponse.Rejected(HostedEndpointFailure.ADMISSION_DEADLINE_EXCEEDED)
            }
            HostedSemanticAdmission.ADMITTED -> {
                trace.emit(HostedEndpointOutcome.COMPLETED)
                trace.enter(HostedTransportStage.EXECUTION)
                dispatch(request).also { trace.emit(it.outcome.transportOutcome()) }
            }
        }
    } finally {
        // Includes cancellation racing with successful lock acquisition and timeout delivery.
        if (holdsLock(ownership)) unlock(ownership)
    }
}
