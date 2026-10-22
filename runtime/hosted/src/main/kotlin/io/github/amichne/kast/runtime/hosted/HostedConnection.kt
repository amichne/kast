package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryExecutionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout

/** One connection owns request I/O, dispatch, and cancellation. Runner-controlled dispatch has no time limit. */
internal suspend fun serveHostedConnection(
    input: InputStream,
    output: OutputStream,
    observer: HostedEndpointObserver,
    limits: ReadLimits = ReadLimits.Default,
    trace: HostedTransportTrace = HostedTransportTrace(observer),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    dispatch: suspend (HostedRequest) -> HostedResponse,
) {
    val writer = HostedResponseWriter(output, observer, limits, trace, ioDispatcher)
    observer.observe(HostedEndpointStage.REQUEST, HostedEndpointOutcome.STARTED)
    try {
        val pending =
            withTimeout(limits[ReadLimitParameter.HOST_CONNECTION_MILLIS].value.toLong()) {
                when (val request = readHostedRequest(input, limits, trace, ioDispatcher)) {
                    is Refinement.Rejected -> {
                        writer.write(HostedResponse.Rejected(request.failure))
                        HostedConnectionDispatch.Completed
                    }
                    is Refinement.Refined ->
                        when (val admitted = admitHostedConnectionDispatch(request.value)) {
                            HostedConnectionRequest.Bounded -> {
                                writer.write(dispatch(request.value))
                                HostedConnectionDispatch.Completed
                            }
                            is HostedConnectionRequest.RunnerControlled ->
                                HostedConnectionDispatch.RunnerControlled(admitted.request)
                        }
                }
            }
        when (pending) {
            HostedConnectionDispatch.Completed -> Unit
            is HostedConnectionDispatch.RunnerControlled -> {
                val response = dispatch(pending.request)
                withTimeout(limits[ReadLimitParameter.HOST_CONNECTION_MILLIS].value.toLong()) {
                    writer.write(response)
                }
            }
        }
    } catch (_: TimeoutCancellationException) {
        trace.emit(HostedEndpointOutcome.REJECTED, failure = HostedEndpointFailure.DEADLINE_EXCEEDED)
        observer.rejected(HostedEndpointStage.REQUEST, HostedEndpointFailure.DEADLINE_EXCEEDED)
    } catch (cancelled: CancellationException) {
        trace.emit(HostedEndpointOutcome.CANCELLED)
        observer.observe(HostedEndpointStage.REQUEST, HostedEndpointOutcome.CANCELLED)
        throw cancelled
    } catch (_: java.io.IOException) {
        trace.emit(HostedEndpointOutcome.REJECTED, failure = HostedEndpointFailure.IO_UNAVAILABLE)
        observer.rejected(HostedEndpointStage.REQUEST, HostedEndpointFailure.IO_UNAVAILABLE)
    } catch (_: RuntimeException) {
        trace.emit(HostedEndpointOutcome.REJECTED, failure = HostedEndpointFailure.PLATFORM_UNAVAILABLE)
        observer.rejected(HostedEndpointStage.REQUEST, HostedEndpointFailure.PLATFORM_UNAVAILABLE)
    }
}

private sealed interface HostedConnectionDispatch {
    data object Completed : HostedConnectionDispatch

    data class RunnerControlled(val request: HostedRequest.Query) : HostedConnectionDispatch
}

private sealed interface HostedConnectionRequest {
    data object Bounded : HostedConnectionRequest

    data class RunnerControlled(val request: HostedRequest.Query) : HostedConnectionRequest
}

private fun admitHostedConnectionDispatch(request: HostedRequest): HostedConnectionRequest =
    when (request) {
        is HostedRequest.Query ->
            when (val query = request.request) {
                is QueryRunRequest.Run ->
                    when (query.executionMode) {
                        QueryExecutionModeDocument.BOUNDED -> HostedConnectionRequest.Bounded
                        QueryExecutionModeDocument.RUNNER_CONTROLLED ->
                            HostedConnectionRequest.RunnerControlled(request)
                    }
                is QueryRunRequest.Resume,
                is QueryRunRequest.ReadResult -> HostedConnectionRequest.Bounded
            }
        is HostedRequest.Describe,
        is HostedRequest.Refresh,
        is HostedRequest.Classes,
        is HostedRequest.Supertype,
        is HostedRequest.PlanChange,
        is HostedRequest.ApplyChange,
        is HostedRequest.RecoverChange,
        is HostedRequest.Source,
        is HostedRequest.Diagnostic -> HostedConnectionRequest.Bounded
    }

private class HostedResponseWriter(
    private val output: OutputStream,
    private val observer: HostedEndpointObserver,
    private val limits: ReadLimits,
    private val trace: HostedTransportTrace,
    private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun write(response: HostedResponse) {
        trace.enter(HostedTransportStage.ENCODING)
        val document = response.document
        trace.emit(HostedEndpointOutcome.COMPLETED, document.toByteArray(Charsets.UTF_8).size.toLong())
        trace.enter(HostedTransportStage.REPLY_WRITE)
        val measuredOutput = HostedMeasuredOutput(output)
        when (val written = runInterruptible(ioDispatcher) { HostedFrames.write(measuredOutput, document, limits) }) {
            is Refinement.Refined -> trace.emit(HostedEndpointOutcome.COMPLETED, measuredOutput.bytes)
            is Refinement.Rejected -> {
                trace.emit(HostedEndpointOutcome.REJECTED, measuredOutput.bytes, written.failure)
                observer.rejected(HostedEndpointStage.REQUEST, written.failure)
                runInterruptible(ioDispatcher) {
                    HostedFrames.write(output, HostedRequests.rejected(written.failure), limits)
                }
                return
            }
        }
        observer.responded(response)
    }
}

/** Counts actual framing bytes at the I/O boundary; no payload is retained. */
private class HostedMeasuredInput(private val input: InputStream) : InputStream() {
    var bytes: Long = 0
        private set

    override fun read(): Int = input.read().also { if (it >= 0) bytes++ }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        input.read(buffer, offset, length).also { if (it > 0) bytes += it }
}

private class HostedMeasuredOutput(private val output: OutputStream) : OutputStream() {
    var bytes: Long = 0
        private set

    override fun write(value: Int) {
        output.write(value)
        bytes++
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        output.write(buffer, offset, length)
        bytes += length
    }

    override fun flush() = output.flush()
}

private suspend fun readHostedRequest(
    input: InputStream,
    limits: ReadLimits,
    trace: HostedTransportTrace,
    ioDispatcher: CoroutineDispatcher,
): Refinement<HostedRequest, HostedEndpointFailure> {
    trace.enter(HostedTransportStage.REQUEST_READ)
    val measuredInput = HostedMeasuredInput(input)
    val request =
        when (val frame = runInterruptible(ioDispatcher) { HostedFrames.read(measuredInput, limits) }) {
            is Refinement.Rejected -> frame
            is Refinement.Refined -> HostedRequests.decode(frame.value)
        }
    trace.emit(
        if (request is Refinement.Refined) HostedEndpointOutcome.COMPLETED else HostedEndpointOutcome.REJECTED,
        measuredInput.bytes,
        (request as? Refinement.Rejected)?.failure,
    )
    return request
}
