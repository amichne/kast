package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout

/** One connection owns one bounded request, including frame I/O and detached dispatch. */
internal suspend fun serveHostedConnection(
    input: InputStream,
    output: OutputStream,
    observer: HostedEndpointObserver,
    limits: ReadLimits = ReadLimits.Default,
    trace: HostedTransportTrace = HostedTransportTrace(observer),
    dispatch: suspend (HostedRequest) -> HostedResponse,
) {
    observer.observe(HostedEndpointStage.REQUEST, HostedEndpointOutcome.STARTED)
    try {
        withTimeout(limits[ReadLimitParameter.HOST_CONNECTION_MILLIS].value.toLong()) {
            val request = readHostedRequest(input, limits, trace)
            val response =
                when (request) {
                    is Refinement.Rejected -> HostedResponse.Rejected(request.failure)
                    is Refinement.Refined -> dispatch(request.value)
                }
            trace.enter(HostedTransportStage.ENCODING)
            val document = response.document
            trace.emit(HostedEndpointOutcome.COMPLETED, document.toByteArray(Charsets.UTF_8).size.toLong())
            trace.enter(HostedTransportStage.REPLY_WRITE)
            val measuredOutput = HostedMeasuredOutput(output)
            when (
                val written = runInterruptible(Dispatchers.IO) { HostedFrames.write(measuredOutput, document, limits) }
            ) {
                is Refinement.Refined -> trace.emit(HostedEndpointOutcome.COMPLETED, measuredOutput.bytes)
                is Refinement.Rejected -> {
                    trace.emit(HostedEndpointOutcome.REJECTED, measuredOutput.bytes, written.failure)
                    observer.rejected(HostedEndpointStage.REQUEST, written.failure)
                    runInterruptible(Dispatchers.IO) {
                        HostedFrames.write(output, HostedRequests.rejected(written.failure), limits)
                    }
                    return@withTimeout
                }
            }
            observer.responded(response)
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
): Refinement<HostedRequest, HostedEndpointFailure> {
    trace.enter(HostedTransportStage.REQUEST_READ)
    val measuredInput = HostedMeasuredInput(input)
    val request =
        when (val frame = runInterruptible(Dispatchers.IO) { HostedFrames.read(measuredInput, limits) }) {
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
